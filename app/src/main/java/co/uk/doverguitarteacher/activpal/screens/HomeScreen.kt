package co.uk.doverguitarteacher.activpal.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import androidx.navigation.NavHostController
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.ktx.userProfileChangeRequest
import androidx.compose.ui.window.Dialog
import com.google.firebase.database.IgnoreExtraProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data class to represent a route stored in Firebase
@IgnoreExtraProperties
data class Route(
    val id: String = "",
    val timestamp: Long = 0,
    val distanceMeters: Float = 0f,
    val elapsedMs: Long = 0,
    val points: List<HashMap<String, Double>> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// MODIFICATION: The function now accepts the NavHostController
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val user = Firebase.auth.currentUser
    var routes by remember { mutableStateOf<List<Route>>(emptyList()) }

    var showProfileDialog by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }
    var expandedMenu by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(user?.displayName ?: "") }
    var savingProfile by remember { mutableStateOf(false) }

    DisposableEffect(user?.uid) {
        val uid = user?.uid
        if (uid != null) {
            val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("routes").child(uid)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newRoutes = snapshot.children.mapNotNull { it.getValue<Route>() }.sortedByDescending { it.timestamp }
                    routes = newRoutes
                }

                override fun onCancelled(error: DatabaseError) {
                    android.util.Log.w("HomeScreen", "loadRoutes:onCancelled", error.toException())
                }
            }
            dbRef.addValueEventListener(listener)
            onDispose { dbRef.removeEventListener(listener) }
        } else {
            onDispose { /* no-op */ }
        }
    }

    fun signOutAndNavigate() {
        Firebase.auth.signOut()
        navController.navigate("login") {
            popUpTo("home") { inclusive = true }
        }
    }

    fun revokeGoogleAccessAndSignOut() {
        // Try to revoke Google access in addition to Firebase signOut (no ID token needed here)
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            client.revokeAccess().addOnCompleteListener {
                Firebase.auth.signOut()
                navController.navigate("login") {
                    popUpTo("home") { inclusive = true }
                }
            }
        } catch (_: Exception) {
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
                if (routes.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("No activities recorded yet.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Press the '+' button to start one!")
                        }
                    }
                } else {
                    items(routes) { route ->
                        RouteCard(route)
                    }
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
                        val update = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                            .setDisplayName(editingName)
                            .build()
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
fun RouteCard(route: Route) {
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
            Text(
                text = "Activity on ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(route.timestamp))}",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(text = "Distance: ${formatDistance(route.distanceMeters)}", color = MaterialTheme.colorScheme.primary)
            Text(text = "Duration: ${formatElapsed(route.elapsedMs)}")
        }
    }
}

private fun formatDistance(m: Float): String = String.format(Locale.US, "%.2f km", m / 1000f)

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
}
