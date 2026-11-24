/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2019 Swift Gan
 * Copyright (C) 2021 LSPosed Contributors
 */
#include <fstream>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <cstring>
#include "elf_util.h"
#include "logging.h"

using namespace SandHook;

ElfImg::ElfImg(std::string_view base_name) : elf(base_name) {
    initModuleBase();
    if (!isValid()) {
        return;
    }

    int fd = open(elf.data(), O_RDONLY);
    if (fd < 0) {
        LOGE("Failed to open %s", elf.data());
        return;
    }

    size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        LOGE("lseek() failed for %s", elf.data());
        close(fd);
        base = nullptr;
        return;
    }

    header = reinterpret_cast<decltype(header)>(mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0));
    close(fd);
    if (header == MAP_FAILED || header == nullptr) {
        LOGE("mmap() failed for %s", elf.data());
        header = nullptr;
        base = nullptr;
        return;
    }
    mapped = true;

    auto in_range = [this](uint64_t off, uint64_t len) -> bool {
        return off <= static_cast<uint64_t>(size) &&
               len <= static_cast<uint64_t>(size) - off;
    };
    auto ptr_at = [this, &in_range](uint64_t off, uint64_t len) -> void* {
        if (!in_range(off, len)) return nullptr;
        return reinterpret_cast<char*>(header) + off;
    };

    if (!in_range(0, sizeof(ElfW(Ehdr))) ||
        memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
        header->e_shentsize < sizeof(ElfW(Shdr)) ||
        header->e_shnum == 0 ||
        header->e_shstrndx >= header->e_shnum) {
        LOGE("ELF header rejected for %s", elf.data());
        base = nullptr;
        return;
    }

    const uint64_t section_table_bytes =
        static_cast<uint64_t>(header->e_shentsize) * static_cast<uint64_t>(header->e_shnum);
    if (!in_range(header->e_shoff, section_table_bytes)) {
        LOGE("ELF section table rejected for %s", elf.data());
        base = nullptr;
        return;
    }

    sectionHeader = reinterpret_cast<decltype(sectionHeader)>(
        ptr_at(header->e_shoff, section_table_bytes));
    if (sectionHeader == nullptr) {
        base = nullptr;
        return;
    }
    auto sectionOffset = reinterpret_cast<uintptr_t>(sectionHeader);

    const auto& shstr = sectionHeader[header->e_shstrndx];
    auto* section = reinterpret_cast<char*>(ptr_at(shstr.sh_offset, shstr.sh_size));
    if (section == nullptr) {
        LOGE("ELF section string table rejected for %s", elf.data());
        base = nullptr;
        return;
    }
    int shnum = header->e_shnum;
    if (shnum < 0 || shnum > 128) shnum = 128;
    for (int i = 0; i < shnum; i++, sectionOffset += header->e_shentsize) {
        auto *section_h = (ElfW(Shdr) *) sectionOffset;
        if (!in_range(
                reinterpret_cast<uintptr_t>(section_h) - reinterpret_cast<uintptr_t>(header),
                sizeof(ElfW(Shdr)))) {
            LOGE("ELF section header out of range for %s", elf.data());
            break;
        }
        if (section_h->sh_name >= shstr.sh_size) {
            continue;
        }
        char *sectionName = section + section_h->sh_name;

        switch (section_h->sh_type) {
            case SHT_DYNSYM: {
                if (bias == -4396 && section_h->sh_entsize >= sizeof(ElfW(Sym)) &&
                    in_range(section_h->sh_offset, section_h->sh_size)) {
                    ElfW(Off) dynsymOffset = section_h->sh_offset;
                    dynsym = section_h;
                    dynsymStart = reinterpret_cast<decltype(dynsymStart)>(
                        ptr_at(dynsymOffset, section_h->sh_size));
                    dynsymCount = section_h->sh_size / section_h->sh_entsize;
                }
                break;
            }

            case SHT_SYMTAB: {
                if (strcmp(sectionName, ".symtab") == 0 &&
                    section_h->sh_entsize >= sizeof(ElfW(Sym)) &&
                    in_range(section_h->sh_offset, section_h->sh_size)) {
                    ElfW(Off) symtabSize = section_h->sh_size;
                    ElfW(Off) symtabOffset = section_h->sh_offset;
                    symtabCount = symtabSize / section_h->sh_entsize;
                    symtabStart = reinterpret_cast<decltype(symtabStart)>(
                        ptr_at(symtabOffset, symtabSize));
                }
                break;
            }

            case SHT_STRTAB: {
                if (bias == -4396 && in_range(section_h->sh_offset, section_h->sh_size)) {
                    ElfW(Off) symstrOffset = section_h->sh_offset;
                    strtab = section_h;
                    strtabStart = reinterpret_cast<decltype(strtabStart)>(
                        ptr_at(symstrOffset, section_h->sh_size));
                    strtabSize = section_h->sh_size;
                }

                if (strcmp(sectionName, ".strtab") == 0 &&
                    in_range(section_h->sh_offset, section_h->sh_size)) {
                    symstrOffsetForSymtab = section_h->sh_offset;
                    symstrSizeForSymtab = section_h->sh_size;
                }
                break;
            }

            case SHT_PROGBITS: {
                if (strtab == nullptr || dynsym == nullptr) {
                    break;
                }

                if (bias == -4396) {
                    bias = (off_t) section_h->sh_addr - (off_t) section_h->sh_offset;
                }
                break;
            }

            case SHT_HASH: {
                if (!in_range(section_h->sh_offset, section_h->sh_size) ||
                    section_h->sh_size < sizeof(ElfW(Word)) * 2) {
                    break;
                }
                auto *d_un = reinterpret_cast<ElfW(Word)*>(
                    ptr_at(section_h->sh_offset, section_h->sh_size));
                nbucket_ = d_un[0];
                nchain_ = d_un[1];
                const uint64_t needed_words = 2ULL + nbucket_ + nchain_;
                if (needed_words * sizeof(ElfW(Word)) <= section_h->sh_size) {
                    bucket_ = d_un + 2;
                    chain_ = bucket_ + nbucket_;
                } else {
                    nbucket_ = 0;
                    nchain_ = 0;
                    bucket_ = nullptr;
                    chain_ = nullptr;
                }
                break;
            }

            case SHT_GNU_HASH: {
                if (!in_range(section_h->sh_offset, section_h->sh_size) ||
                    section_h->sh_size < sizeof(ElfW(Word)) * 4) {
                    break;
                }
                auto *d_buf = reinterpret_cast<ElfW(Word) *>(
                    ptr_at(section_h->sh_offset, section_h->sh_size));
                gnu_nbucket_ = d_buf[0];
                gnu_symndx_ = d_buf[1];
                gnu_bloom_size_ = d_buf[2];
                gnu_shift2_ = d_buf[3];
                const uint64_t bloom_words =
                    static_cast<uint64_t>(gnu_bloom_size_) *
                    (sizeof(uintptr_t) / sizeof(ElfW(Word)));
                const uint64_t min_words = 4ULL + bloom_words + gnu_nbucket_;
                if (gnu_nbucket_ == 0 || gnu_bloom_size_ == 0 ||
                    min_words * sizeof(ElfW(Word)) > section_h->sh_size) {
                    gnu_nbucket_ = 0;
                    gnu_bloom_size_ = 0;
                    break;
                }
                gnu_bloom_filter_ = reinterpret_cast<decltype(gnu_bloom_filter_)>(d_buf + 4);
                gnu_bucket_ = reinterpret_cast<decltype(gnu_bucket_)>(gnu_bloom_filter_ + gnu_bloom_size_);
                gnu_chain_ = gnu_bucket_ + gnu_nbucket_ - gnu_symndx_;
                gnu_chain_count_ = static_cast<uint32_t>(
                    (section_h->sh_size / sizeof(ElfW(Word))) - min_words);
                break;
            }
        }
    }
}

ElfImg::~ElfImg() {
    if (mapped && header != nullptr) {
        munmap(header, size);
    }
}

ElfW(Addr) ElfImg::elfLookup(std::string_view name, uint32_t hash) const {
    if (nbucket_ == 0 || nchain_ == 0 || bucket_ == nullptr || chain_ == nullptr ||
        dynsymStart == nullptr || strtabStart == nullptr || strtabSize == 0) {
        return 0;
    }

    char *strings = (char *) strtabStart;
    uint32_t guard = 0;
    for (auto n = bucket_[hash % nbucket_]; n != 0 && n < nchain_ && guard++ < nchain_; n = chain_[n]) {
        if (dynsymCount > 0 && n >= dynsymCount) return 0;
        auto *sym = dynsymStart + n;
        if (sym->st_name < strtabSize && name == strings + sym->st_name) {
            return sym->st_value;
        }
    }
    return 0;
}

ElfW(Addr) ElfImg::gnuLookup(std::string_view name, uint32_t hash) const {
    static constexpr auto bloomMaskBits = sizeof(ElfW(Addr)) * 8;
    if (gnu_nbucket_ == 0 || gnu_bloom_size_ == 0 ||
        gnu_bloom_filter_ == nullptr || gnu_bucket_ == nullptr || gnu_chain_ == nullptr ||
        dynsymStart == nullptr || strtabStart == nullptr || strtabSize == 0) {
        return 0;
    }

    auto bloomWord = gnu_bloom_filter_[(hash / bloomMaskBits) % gnu_bloom_size_];
    uintptr_t mask = 0 | (uintptr_t) 1 << (hash % bloomMaskBits) | (uintptr_t) 1 << ((hash >> gnu_shift2_) % bloomMaskBits);
    if ((mask & bloomWord) == mask) {
        auto symIndex = gnu_bucket_[hash % gnu_nbucket_];
        if (symIndex >= gnu_symndx_) {
            char *strings = (char *) strtabStart;
            uint32_t guard = 0;
            do {
                if (dynsymCount > 0 && symIndex >= dynsymCount) return 0;
                if (symIndex - gnu_symndx_ >= gnu_chain_count_) return 0;
                auto *sym = dynsymStart + symIndex;
                if (((gnu_chain_[symIndex] ^ hash) >> 1) == 0
                    && sym->st_name < strtabSize
                    && name == strings + sym->st_name) {
                    return sym->st_value;
                }
            } while ((gnu_chain_[symIndex++] & 1) == 0 && guard++ < gnu_chain_count_);
        }
    }
    return 0;
}

ElfW(Addr) ElfImg::linearLookup(std::string_view name) const {
    initLinearMap();
    if (auto i = symtabs_.find(name); i != symtabs_.end()) {
        return i->second->st_value;
    }
    return 0;
}

ElfW(Addr) ElfImg::prefixLookup(std::string_view prefix) const {
    initLinearMap();
    if (auto i = symtabs_.lower_bound(prefix); i != symtabs_.end() && i->first.starts_with(prefix)) {
        LOGD("Found prefix %s of %s at offset %p in %s at symtab section by linear lookup", prefix.data(), i->first.data(),
             reinterpret_cast<void *>(i->second->st_value), elf.data());
        return i->second->st_value;
    }
    return 0;
}

ElfW(Addr) ElfImg::getSymbolOffset(std::string_view name, uint32_t gnuHash, uint32_t elfHash) const {
    if (auto offset = gnuLookup(name, gnuHash); offset > 0) {
        LOGD("Found JNI method %s at offset %p in %s at dynsym section by gnu hash", name.data(), reinterpret_cast<void *>(offset), elf.data());
        return offset;
    } else if (offset = elfLookup(name, elfHash); offset > 0) {
        LOGD("Found JNI method %s at offset %p in %s at dynsym section by elf hash", name.data(), reinterpret_cast<void *>(offset), elf.data());
        return offset;
    } else if (offset = linearLookup(name); offset > 0) {
        LOGD("Found JNI method %s at offset %p in %s at symtab section by linear lookup", name.data(), reinterpret_cast<void *>(offset), elf.data());
        return offset;
    }
    return 0;
}

void ElfImg::initLinearMap() const {
    if (symtabs_.empty()) {
        if (symtabStart != nullptr && symstrOffsetForSymtab != 0 && symstrSizeForSymtab > 0) {
            for (ElfW(Off) i = 0; i < symtabCount; i++) {
                unsigned int st_type = ELF_ST_TYPE(symtabStart[i].st_info);
                if (symtabStart[i].st_name >= symstrSizeForSymtab) {
                    continue;
                }
                const char *st_name = offsetOf<const char *>(header, symstrOffsetForSymtab + symtabStart[i].st_name);
                if ((st_type == STT_FUNC || st_type == STT_OBJECT) && symtabStart[i].st_size) {
                    symtabs_.emplace(st_name, &symtabStart[i]);
                }
            }
        }
    }
}

void ElfImg::initModuleBase() {
    std::string mapsPath = std::string("/proc/self/maps");
    std::ifstream mapsStream(mapsPath);

    if (base) {
        return;
    }

    if (mapsStream.is_open()) {
        std::string line;
        while (getline(mapsStream, line)){
            if (line.find(elf) != std::string::npos) {
                const auto dash = line.find('-');
                const auto slash = line.find('/');
                if (dash == std::string::npos || slash == std::string::npos || dash == 0) {
                    continue;
                }
                std::string baseAddress = line.substr(0, dash);
                char* end = nullptr;
                auto parsed = strtoull(baseAddress.c_str(), &end, 16);
                if (end == baseAddress.c_str() || parsed == 0) {
                    continue;
                }
                std::string elfPath = line.substr(slash);
                base = reinterpret_cast<void *>(parsed);
                elf = elfPath;
                break;
            }
        }
        mapsStream.close();
    }
}
