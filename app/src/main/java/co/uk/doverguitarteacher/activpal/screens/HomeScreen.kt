package co.uk.doverguitarteacher.activpal.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import androidx.navigation.NavHostController
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.ktx.userProfileChangeRequest
import androidx.compose.ui.window.Dialog

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

    var showProfileDialog by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }
    var expandedMenu by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(user?.displayName ?: "") }
    var savingProfile by remember { mutableStateOf(false) }

    fun signOutAndNavigate() {
        Firebase.auth.signOut()
        navController.navigate("login") {
            popUpTo("home") { inclusive = true }
        }
    }

    fun revokeGoogleAccessAndSignOut() {
        // Try to revoke Google access in addition to Firebase signOut
        try {
            val defaultWebClientId = try {
                context.getString(co.uk.doverguitarteacher.activpal.R.string.default_web_client_id)
            } catch (_: Exception) {
                ""
            }
            val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
            if (defaultWebClientId.isNotBlank()) gsoBuilder.requestIdToken(defaultWebClientId)
            val gso = gsoBuilder.build()
            val client = GoogleSignIn.getClient(context, gso)
            client.revokeAccess().addOnCompleteListener {
                // Regardless of revoke result, sign out locally
                Firebase.auth.signOut()
                navController.navigate("login") {
                    popUpTo("home") { inclusive = true }
                }
            }
        } catch (e: Exception) {
            // Fallback: just sign out
            Firebase.auth.signOut()
            navController.navigate("login") {
                popUpTo("home") { inclusive = true }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Show avatar + title
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Compact avatar in top bar
                        if (user?.photoUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(user.photoUrl)
                                    .crossfade(true)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build(),
                                contentDescription = "Profile photo",
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
                                    .clickable { showProfileDialog = true },
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            // Fallback circle with person icon
                            Surface(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable { showProfileDialog = true },
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
                    // Overflow menu for sign out / revoke
                    IconButton(onClick = { expandedMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = expandedMenu, onDismissRequest = { expandedMenu = false }) {
                        DropdownMenuItem(text = { Text("Profile") }, onClick = {
                            expandedMenu = false
                            showProfileDialog = true
                        })
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                            expandedMenu = false
                            signOutAndNavigate()
                        })
                        DropdownMenuItem(text = { Text("Revoke Google access") }, onClick = {
                            expandedMenu = false
                            revokeGoogleAccessAndSignOut()
                        })
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
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {

            // Profile summary area at top of content
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (user?.photoUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(user.photoUrl)
                                .crossfade(true)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            contentDescription = "Profile photo",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { showFullImage = true },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .size(80.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.08f)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Profile placeholder",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(text = user?.displayName ?: "No name", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(text = user?.email ?: "No email", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { showProfileDialog = true }) {
                            Text("View / Edit profile")
                        }
                    }
                }
            }

            // Activity feed below
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                items(dummyActivities) { activity ->
                    ActivityCard(activity)
                }
            }
        }

        // Profile dialog for viewing/updating displayName
        if (showProfileDialog) {
            AlertDialog(
                onDismissRequest = { showProfileDialog = false },
                title = { Text("Profile") },
                text = {
                    Column {
                        if (user?.photoUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(user.photoUrl).crossfade(true).build(),
                                contentDescription = "Profile photo",
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape)
                                    .align(Alignment.CenterHorizontally),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            label = { Text("Display name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Email: ${user?.email ?: ""}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // Save profile changes
                        savingProfile = true
                        val update = userProfileChangeRequest { displayName = editingName }
                        user?.updateProfile(update)?.addOnCompleteListener { t ->
                            savingProfile = false
                            showProfileDialog = false
                            if (!t.isSuccessful) {
                                // show a toast on failure
                                android.widget.Toast.makeText(context, "Profile update failed: ${t.exception?.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }) {
                        if (savingProfile) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showProfileDialog = false }) { Text("Close") }
                }
            )
        }

        // Full screen image viewer
        if (showFullImage && user?.photoUrl != null) {
            Dialog(onDismissRequest = { showFullImage = false }) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(user.photoUrl).crossfade(true).build(),
                            contentDescription = "Full profile photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
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
