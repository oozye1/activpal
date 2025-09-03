package co.uk.doverguitarteacher.activpal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import co.uk.doverguitarteacher.activpal.screens.HomeScreen
import co.uk.doverguitarteacher.activpal.screens.LoginScreen
import co.uk.doverguitarteacher.activpal.screens.RecordScreen
import co.uk.doverguitarteacher.activpal.screens.RegisterScreen
import co.uk.doverguitarteacher.activpal.ui.theme.ActivpalTheme
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Firebase is auto-initialized when google-services.json is present and
        // the Google Services Gradle plugin is applied — no manual initialization needed here.

        setContent {
            ActivpalTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // Pick initial screen based on whether a Firebase user is already signed in.
    val startDestination = if (Firebase.auth.currentUser != null) "home" else "login"

    // If you want to react to auth state changes during composition, you can use LaunchedEffect
    LaunchedEffect(Firebase.auth.currentUser) {
        // No-op for now; keeps composition aware of currentUser for recomposition if it changes
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("login") {
            LoginScreen(navController)
        }
        composable("register") {
            RegisterScreen(navController)
        }
        composable("home") {
            HomeScreen(navController)
        }
        composable("record") {
            RecordScreen(navController)
        }
    }
}
