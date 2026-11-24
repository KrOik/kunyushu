package tv.kyshu.ui.viewmodel

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    /* Fab */
    var mFabOpened = false
}