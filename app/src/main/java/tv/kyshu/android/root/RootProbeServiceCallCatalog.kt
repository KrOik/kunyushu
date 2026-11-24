package tv.kyshu.android.root

/**
 * Catalog for `service call` transactions that are approved for root probes.
 *
 * Transaction IDs are Android/ROM specific and can be write-capable. The
 * default catalog is intentionally empty until a transaction has documented
 * read-only evidence for the target SDK/ROM family.
 */
object RootProbeServiceCallCatalog {
    data class Transaction(
        val service: String,
        val code: Int,
        val purpose: String,
        val expectedFields: List<String>,
    ) {
        init {
            require(SERVICE_NAME_PATTERN.matches(service)) { "unsafe_service_name" }
            require(service !in BLOCKED_SERVICES) { "sensitive_service" }
            require(code in 1..9999) { "unsafe_transaction_code" }
            require(purpose.isNotBlank() && purpose.length <= 120) { "unsafe_transaction_purpose" }
            require(expectedFields.isNotEmpty()) { "missing_expected_fields" }
            require(expectedFields.all { FIELD_NAME_PATTERN.matches(it) }) { "unsafe_expected_field" }
        }
    }

    fun registeredTransactions(): List<Transaction> = APPROVED_READ_ONLY_TRANSACTIONS

    fun isAllowlisted(argv: List<String>): Boolean {
        if (argv.size != 4) return false
        if (argv[0] != "service" || argv[1] != "call") return false
        val service = argv[2]
        val code = argv[3].toIntOrNull() ?: return false
        return APPROVED_READ_ONLY_TRANSACTIONS.any {
            it.service == service && it.code == code
        }
    }

    private val APPROVED_READ_ONLY_TRANSACTIONS = emptyList<Transaction>()

    private val BLOCKED_SERVICES = setOf(
        "iphonesubinfo",
        "phone",
        "isub",
        "isms",
        "carrier_config",
        "device_identifiers",
    )
    private val SERVICE_NAME_PATTERN = Regex("""[a-z][a-z0-9_.-]{0,80}""")
    private val FIELD_NAME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_.\-\[\]]{0,120}""")
}
