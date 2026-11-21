package com.example.myapplication.loopbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Lets the dock's search button open the search on the Browse home page. */
object BrowseSearchBus {
    var active by mutableStateOf(false)
}
