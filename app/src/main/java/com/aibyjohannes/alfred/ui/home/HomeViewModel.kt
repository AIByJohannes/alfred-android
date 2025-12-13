package com.aibyjohannes.alfred.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "My name is A.L.F.R.E.D."
    }
    val text: LiveData<String> = _text
}