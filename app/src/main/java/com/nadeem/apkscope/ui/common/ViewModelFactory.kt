package com.nadeem.apkscope.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/** Minimal factory for the several session-scoped ViewModels below (`AnalysisViewModel(sessionId)`, etc.) — no DI framework introduced in this checkpoint, matching the rest of this module's dependency style. */
class SimpleViewModelFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
 @Suppress("UNCHECKED_CAST")
 override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

@androidx.compose.runtime.Composable
inline fun <reified T : ViewModel> sessionViewModel(noinline create: () -> T): T =
 viewModel(factory = SimpleViewModelFactory(create))
