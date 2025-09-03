package co.uk.doverguitarteacher.activpal.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity Feed") },
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
