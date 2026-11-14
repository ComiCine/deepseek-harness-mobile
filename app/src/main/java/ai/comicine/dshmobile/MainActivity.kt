package ai.comicine.dshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.comicine.dshmobile.ui.HarnessApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: HarnessViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )
            HarnessApp(model)
        }
    }
}
