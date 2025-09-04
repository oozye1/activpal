package co.uk.doverguitarteacher.activpal.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import androidx.navigation.NavHostController
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color

// A simple data class to represent a fake activity
data class Activity(val userName: String, val type: String, val distance: String)

// A list of fake activities to show in our feed
val dummyActivities = listOf(
    Activity("John Doe", "Morning Run", "5.2 km"),
    Activity("Jane Smith", "Afternoon Cycling", "15.7 km"),
    Activity("Peter Jones", "Evening Walk", "2.1 km"),
    Activity("John Doe", "Weekend Hike", "10.5 km")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// MODIFICATION: The function now accepts the NavHostController
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val user = Firebase.auth.currentUser

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Show avatar + title
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (user?.photoUrl != null) {
                            AsyncImage(
                                model = user.photoUrl,
                                contentDescription = "Profile photo",
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            // Fallback circle with person icon
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Profile placeholder",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Activity Feed")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Sign out
                        Firebase.auth.signOut()
                        // Navigate back to login and clear backstack
                        navController.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            // MODIFICATION: The onClick now navigates to the "record" screen
            FloatingActionButton(onClick = { navController.navigate("record") }) {
                Icon(Icons.Default.Add, contentDescription = "Start new activity")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(8.dp)
        ) {
            items(dummyActivities) { activity ->
                ActivityCard(activity)
            }
        }
    }
}

@Composable
fun ActivityCard(activity: Activity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = activity.userName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(text = activity.type, color = MaterialTheme.colorScheme.primary)
            Text(text = "Distance: ${activity.distance}")
        }
    }
}
