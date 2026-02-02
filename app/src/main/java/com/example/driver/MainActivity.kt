package com.example.driver

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.firebase.FirebaseApp
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import android.util.Log


class MainActivity : ComponentActivity() {

    private val BASE_URL = "http://10.113.82.165:3000"
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    companion object {
        const val SCHOOL_LAT = 12.962500
        const val SCHOOL_LNG = 80.057831
    }


    private fun prefs(): SharedPreferences =
        getSharedPreferences("smartbus", Context.MODE_PRIVATE)

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLocationUpdates()
            else Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        setContent { AppRoot() }
    }

    @SuppressLint("MissingPermission")
    private fun saveStudentHome(busId: String) {

        // 🔒 Permission safety check
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(this)

        client.lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener

                val lat = loc.latitude
                val lng = loc.longitude

                /* ================= FIREBASE ================= */

                FirebaseDatabase.getInstance()
                    .getReference("students/$busId/home")
                    .setValue(
                        mapOf(
                            "lat" to lat,
                            "lng" to lng
                        )
                    )

                /* ================= BACKEND ================= */

                // Save once in backend DB (for reports / reuse)
                postToBackend(
                    path = "/students/save-home",
                    busId = busId,
                    type = "$lat,$lng"
                )
            }
            .addOnFailureListener {
                // Optional: log error (no crash)
            }
    }

    @SuppressLint("MissingPermission")
    private fun saveStudentBoarding(
        context: Context,
        regNo: String,
        phone: String
    ) {
        // 🔒 Permission safety check
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(context)

        client.lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener

                val json = JSONObject().apply {
                    put("regNo", regNo)
                    put("phone", phone)
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                }

                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())

                OkHttpClient().newCall(
                    Request.Builder()
                        .url("$BASE_URL/students/save-boarding")
                        .post(body)
                        .build()
                ).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        // no crash
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // success / ignore
                    }
                })
            }
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    fun animateMarker(
        marker: Marker,
        start: LatLng,
        end: LatLng
    ) {
        val handler = android.os.Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val duration = 1000L // 1 second animation

        val bearing = getBearing(start, end)

        handler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)

                val lat = (1 - t) * start.latitude + t * end.latitude
                val lng = (1 - t) * start.longitude + t * end.longitude

                marker.position = LatLng(lat, lng)
                marker.rotation = bearing
                marker.isFlat = true

                if (t < 1f) {
                    handler.postDelayed(this, 16) // ~60 FPS
                }
            }
        })
    }

    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(
            a.latitude, a.longitude,
            b.latitude, b.longitude,
            result
        )
        return result[0].toDouble()
    }

    fun getBearing(start: LatLng, end: LatLng): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)

        val dLon = lon2 - lon1

        val y = Math.sin(dLon) * Math.cos(lat2)
        val x =
            Math.cos(lat1) * Math.sin(lat2) -
                    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)

        return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
    }

    fun resizedMarker(
        context: Context,
        resId: Int,
        width: Int,
        height: Int
    ): BitmapDescriptor {
        val imageBitmap =
            BitmapFactory.decodeResource(context.resources, resId)

        val scaledBitmap =
            Bitmap.createScaledBitmap(imageBitmap, width, height, false)

        return BitmapDescriptorFactory.fromBitmap(scaledBitmap)
    }

    fun zoomForDistance(distanceKm: Double): Float {
        Log.d("ZOOM_CHECK", "zoomForDistance called with $distanceKm km")

        return when {
            distanceKm < 0.3 -> 19f
            distanceKm < 1.0 -> 18f
            distanceKm < 3.0 -> 16.5f
            distanceKm < 8.0 -> 15f
            else -> 13.5f
        }
    }


    fun autoTripMode(): String {
        val hour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)

        return if (hour < 12) "MORNING" else "EVENING"
    }

    fun fitCamera(map: GoogleMap, a: LatLng, b: LatLng) {
        val bounds = LatLngBounds.builder()
            .include(a)
            .include(b)
            .build()

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 180)
        )
    }

    fun showStudentNotification(
        context: Context,
        title: String,
        message: String
    ) {
        val channelId = "bus_alerts"

        // ✅ Android 13+ permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return // ❌ no permission, no crash
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bus Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.bus_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(1001, notification)
    }
    // ================= DATA MODELS =================
    fun saveNotification(
        busId: String,
        regNo: String,
        title: String,
        message: String
    ) {
        FirebaseDatabase.getInstance()
            .getReference("notifications/$busId/$regNo")
            .push()
            .setValue(
                mapOf(
                    "title" to title,
                    "message" to message,
                    "time" to System.currentTimeMillis(),
                    "read" to false   // ✅ REQUIRED
                )
            )
    }


    fun refreshStudentBus(
        regNo: String,
        phone: String,
        onUpdated: (String) -> Unit
    ) {
        val url = "$BASE_URL/students/current-bus?regNo=$regNo&phone=$phone"

        OkHttpClient().newCall(
            Request.Builder().url(url).build()
        ).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return

                val newBusId =
                    JSONObject(response.body!!.string()).getString("busId")

                runOnUiThread {
                    onUpdated(newBusId)
                }
            }
        })
    }

    fun refreshDriverBus(
        phone: String,
        onUpdated: (String) -> Unit
    ) {
        val json = JSONObject().put("phone", phone)
        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        OkHttpClient().newCall(
            Request.Builder()
                .url("$BASE_URL/drivers/check-driver")
                .post(body)
                .build()
        ).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return

                val newBusId =
                    JSONObject(response.body!!.string())
                        .getJSONObject("driver")
                        .getString("busId")

                runOnUiThread {
                    onUpdated(newBusId)
                }
            }
        })
    }
    @Composable
    fun DriverMap(
        lat: Double,
        lng: Double,
        tripMode: String
    ) {
        BusMap(
            lat = lat,
            lng = lng,
            tripMode = tripMode,
            homeLat = null,
            homeLng = null,
            userType = "DRIVER",
            onEtaUpdate = {}
        )
    }

    @Composable
    fun StudentMap(
        lat: Double,
        lng: Double,
        tripMode: String,
        homeLat: Double?,
        homeLng: Double?,
        onEta: (Int) -> Unit
    ) {
        BusMap(
            lat = lat,
            lng = lng,
            tripMode = tripMode,
            homeLat = homeLat,
            homeLng = homeLng,
            userType = "STUDENT",
            onEtaUpdate = onEta
        )
    }

    /* ================= ROOT ================= */

    @Composable
    fun AppRoot() {
        val context = LocalContext.current
        val p = prefs()

        var role by remember { mutableStateOf(p.getString("role", null)) }
        var busId by remember { mutableStateOf(p.getString("busId", null)) }

        /* ================= AUTO REFRESH BUS ID (STUDENT + DRIVER) ================= */

        LaunchedEffect(role) {

            // 🔄 STUDENT AUTO REFRESH
            if (role == "STUDENT" && busId != null) {

                val regNo = p.getString("regNo", null)
                val phone = p.getString("phone", null)

                if (regNo != null && phone != null) {
                    refreshStudentBus(regNo, phone) { latestBus: String ->
                        if (latestBus != busId) {
                            p.edit().putString("busId", latestBus).apply()
                            busId = latestBus
                        }
                    }
                }
            }

            // 🔄 DRIVER AUTO REFRESH
            if (role == "DRIVER" && busId != null) {

                val phone = p.getString("phone", null)

                if (phone != null) {
                    refreshDriverBus(phone) { latestBus ->
                        if (latestBus != busId) {
                            p.edit().putString("busId", latestBus).apply()
                            busId = latestBus
                        }
                    }
                }
            }
        }



        /* ================= UI FLOW ================= */

        when {

            role == null -> RoleScreen {
                role = it
                p.edit().putString("role", it).apply()
            }

            role == "DRIVER" && busId == null ->
                DriverLogin { phone ->
                    verifyDriver(phone) { bus ->
                        p.edit()
                            .putString("role", "DRIVER")
                            .putString("busId", bus)
                            .putString("phone", phone) // 🔥 SAVE PHONE
                            .apply()
                        busId = bus
                    }
                }

            role == "STUDENT" && busId == null ->
                StudentLogin { reg, phone ->
                    verifyStudent(reg, phone) { bus ->

                        p.edit()
                            .putString("role", "STUDENT")
                            .putString("busId", bus)
                            .putString("regNo", reg)     // 🔥 SAVE REG NO
                            .putString("phone", phone)  // 🔥 SAVE PHONE
                            .apply()

                        FirebaseMessaging.getInstance().subscribeToTopic(bus)

                        // 🏠 save home once
                        saveStudentHome(bus)

                        busId = bus
                    }
                }

            role == "DRIVER" -> DriverHome(busId!!)

            role == "STUDENT" -> StudentHome(busId!!) {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(busId!!)
                p.edit().clear().apply()
                role = null
                busId = null
            }
        }
    }

    /* ================= COMMON UI ================= */

    @Composable
    fun CenterText(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text)
        }
    }

    @Composable
    fun RoleScreen(onSelect: (String) -> Unit) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Button(onClick = { onSelect("DRIVER") }) { Text("🚍 Driver") }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSelect("STUDENT") }) { Text("🎓 Student") }
        }
    }

    @Composable
    fun DriverLogin(onLogin: (String) -> Unit) {
        var phone by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone") })
            Button(onClick = { onLogin(phone) }) { Text("Login") }
        }
    }

    @Composable
    fun StudentLogin(
        onLogin: (String, String) -> Unit
    ) {
        var reg by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = "Student Login",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = reg,
                onValueChange = { reg = it },
                label = { Text("Register Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { ch -> ch.isDigit() } },
                label = { Text("Phone Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = reg.isNotBlank() && phone.length >= 10,
                onClick = {
                    onLogin(
                        reg.trim().uppercase(),
                        phone.trim()
                    )
                }
            ) {
                Text("Login")
            }
        }
    }


    /* ================= DRIVER HOME ================= */
    @Composable
    fun DriverHome(busId: String) {

        val context = LocalContext.current

        var lat by remember { mutableStateOf(0.0) }
        var lng by remember { mutableStateOf(0.0) }

        var tripStatus by remember { mutableStateOf("ENDED") }
        var tripMode by remember { mutableStateOf("MORNING") }

        /* ================= FIREBASE LISTENERS ================= */

        DisposableEffect(busId) {

            val busRef =
                FirebaseDatabase.getInstance().getReference("buses/$busId")

            val tripRef =
                FirebaseDatabase.getInstance().getReference("busTrips/$busId/status")

            val modeRef =
                FirebaseDatabase.getInstance().getReference("busTrips/$busId/mode")

            val busListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    lat = snapshot.child("lat").getValue(Double::class.java) ?: 0.0
                    lng = snapshot.child("lng").getValue(Double::class.java) ?: 0.0
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            val tripListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    tripStatus = snapshot.getValue(String::class.java) ?: "ENDED"
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            val modeListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    tripMode = snapshot.getValue(String::class.java) ?: "MORNING"
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            busRef.addValueEventListener(busListener)
            tripRef.addValueEventListener(tripListener)
            modeRef.addValueEventListener(modeListener)

            onDispose {
                busRef.removeEventListener(busListener)
                tripRef.removeEventListener(tripListener)
                modeRef.removeEventListener(modeListener)
            }
        }

        /* ================= UI ================= */

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            Text(text = "🚌 Bus ID: $busId")
            Text(text = "📍 Trip Status: $tripStatus")
            Text(text = "🧭 Mode: $tripMode")

            Spacer(modifier = Modifier.height(12.dp))

            // ▶ START TRIP
            Button(
                enabled = tripStatus != "STARTED",
                onClick = {

                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        locationPermissionLauncher.launch(
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        return@Button
                    }

                    FirebaseDatabase.getInstance()
                        .getReference("busTrips/$busId")
                        .setValue(
                            mapOf(
                                "status" to "STARTED",
                                "mode" to autoTripMode()
                            )
                        )

                    postToBackend("/drivers/start-trip", busId)
                    startLocationUpdates()
                }
            ) {
                Text("▶ Start Trip")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ⏹ END TRIP
            Button(
                enabled = tripStatus == "STARTED",
                onClick = {

                    FirebaseDatabase.getInstance()
                        .getReference("busTrips/$busId/status")
                        .setValue("ENDED")

                    postToBackend("/drivers/end-trip", busId)
                    stopLocationUpdates()
                }
            ) {
                Text("⏹ End Trip")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                Button(
                    onClick = {
                        FirebaseDatabase.getInstance()
                            .getReference("busTrips/$busId/mode")
                            .setValue("MORNING")
                    }
                ) {
                    Text("🌅 Morning")
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        FirebaseDatabase.getInstance()
                            .getReference("busTrips/$busId/mode")
                            .setValue("EVENING")
                    }
                ) {
                    Text("🌆 Evening")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            /* ================= MAP ================= */

            if (tripStatus == "STARTED") {

                val safeLat = if (lat == 0.0) SCHOOL_LAT else lat
                val safeLng = if (lng == 0.0) SCHOOL_LNG else lng

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    DriverMap(
                        lat = safeLat,
                        lng = safeLng,
                        tripMode = tripMode
                    )
                }

                if (lat == 0.0 && lng == 0.0) {
                    Spacer(Modifier.height(8.dp))
                    Text("📡 Waiting for GPS signal…")
                }

            } else {
                Spacer(modifier = Modifier.height(40.dp))
                CenterText("🚫 Trip not started")
            }
        }
    }



    /* ================= STUDENT HOME ================= */
    @Composable
    fun StudentHome(
        busId: String,
        onLogout: () -> Unit
    ) {
        val context = LocalContext.current
        val regNo = prefs().getString("regNo", "") ?: ""

        // 🔔 notification screen control
        var showNotifications by remember { mutableStateOf(false) }

        var lat by remember { mutableStateOf(0.0) }
        var lng by remember { mutableStateOf(0.0) }
        var started by remember { mutableStateOf(false) }

        // 🏠 Student home
        var homeLat by remember { mutableStateOf<Double?>(null) }
        var homeLng by remember { mutableStateOf<Double?>(null) }

        // 📏 Distance & ETA
        var distance by remember { mutableStateOf(0.0) }
        var eta by remember { mutableStateOf(0) }
        var tripMode by remember { mutableStateOf(autoTripMode()) }

        // 🚍 Speed & update time
        var speed by remember { mutableStateOf(0.0) }
        var lastUpdate by remember { mutableStateOf("") }

        // 🔔 Notification control (1 km)
        var alertShown by remember { mutableStateOf(false) }

        // 🔧 Speed helpers
        var lastBusPos by remember { mutableStateOf<LatLng?>(null) }
        var lastBusTime by remember { mutableStateOf<Long?>(null) }
        var unreadCount by remember { mutableStateOf(0) }

        DisposableEffect(busId, regNo) {
            val ref = FirebaseDatabase.getInstance()
                .getReference("notifications/$busId/$regNo")

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    unreadCount = snapshot.children.count {
                        it.child("read").getValue(Boolean::class.java) == false
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            ref.addValueEventListener(listener)
            onDispose { ref.removeEventListener(listener) }
        }

        /* ================= FIREBASE LISTENERS ================= */

        DisposableEffect(busId) {

            val busRef =
                FirebaseDatabase.getInstance().getReference("buses/$busId")

            val tripRef =
                FirebaseDatabase.getInstance().getReference("busTrips/$busId/status")

            val modeRef =
                FirebaseDatabase.getInstance().getReference("busTrips/$busId/mode")

            val homeRef =
                FirebaseDatabase.getInstance().getReference("students/$busId/home")

            val busListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    val newLat =
                        snapshot.child("lat").getValue(Double::class.java) ?: return
                    val newLng =
                        snapshot.child("lng").getValue(Double::class.java) ?: return

                    lat = newLat
                    lng = newLng

                    val busPos = LatLng(newLat, newLng)
                    val now = System.currentTimeMillis()

                    // SPEED
                    if (lastBusPos != null && lastBusTime != null) {
                        val meters = distanceMeters(lastBusPos!!, busPos)
                        val seconds = (now - lastBusTime!!) / 1000.0
                        if (seconds > 0) {
                            speed = (meters / seconds) * 3.6
                        }
                    }

                    lastBusPos = busPos
                    lastBusTime = now

                    lastUpdate = java.text.SimpleDateFormat(
                        "hh:mm a",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date())

                    val targetPos = if (tripMode == "MORNING") {
                        LatLng(SCHOOL_LAT, SCHOOL_LNG)
                    } else {
                        if (homeLat != null && homeLng != null) {
                            LatLng(homeLat!!, homeLng!!)
                        } else {
                            LatLng(SCHOOL_LAT, SCHOOL_LNG)
                        }
                    }

                    distance = distanceInKm(busPos, targetPos)
                    eta = etaMinutes(distance)

                    // 🔔 1 KM NOTIFICATION
                    if (distance <= 1.0 && !alertShown) {

                        val title = "🚌 Bus is 1 km away"
                        val message = "Please get ready, the bus will arrive soon."

                        showStudentNotification(context, title, message)

                        saveNotification(
                            busId = busId,
                            regNo = regNo,
                            title = title,
                            message = message
                        )

                        alertShown = true
                    }

                    if (distance > 1.2) {
                        alertShown = false
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }

            val tripListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    started = snapshot.getValue(String::class.java) == "STARTED"
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            val modeListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    tripMode = snapshot.getValue(String::class.java) ?: "MORNING"
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            val homeListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    homeLat = snapshot.child("lat").getValue(Double::class.java)
                    homeLng = snapshot.child("lng").getValue(Double::class.java)
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            busRef.addValueEventListener(busListener)
            tripRef.addValueEventListener(tripListener)
            modeRef.addValueEventListener(modeListener)
            homeRef.addValueEventListener(homeListener)

            onDispose {
                busRef.removeEventListener(busListener)
                tripRef.removeEventListener(tripListener)
                modeRef.removeEventListener(modeListener)
                homeRef.removeEventListener(homeListener)
            }
        }


        @Composable
        fun StudentNotificationsScreen(
            busId: String,
            regNo: String,
            onClose: () -> Unit
        ) {
            var notifications by remember { mutableStateOf(listOf<DataSnapshot>()) }

            DisposableEffect(busId, regNo) {
                val ref = FirebaseDatabase.getInstance()
                    .getReference("notifications/$busId/$regNo")

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        notifications = snapshot.children.toList()

                        // ✅ mark all as read
                        snapshot.children.forEach {
                            it.ref.child("read").setValue(true)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                }

                ref.addValueEventListener(listener)
                onDispose { ref.removeEventListener(listener) }
            }

            /* ---------- GROUP BY DATE ---------- */
            val grouped = notifications.groupBy { snap ->
                val time = snap.child("time").getValue(Long::class.java) ?: 0L
                java.text.SimpleDateFormat(
                    "dd MMM yyyy",
                    java.util.Locale.getDefault()
                ).format(java.util.Date(time))
            }

            /* ---------- UI ---------- */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Column {

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🔔 Notifications",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Text(
                            text = "Close",
                            modifier = Modifier.clickable { onClose() }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (notifications.isEmpty()) {
                        Text("No notifications yet")
                    } else {

                        grouped
                            .toSortedMap(compareByDescending { it }) // latest date first
                            .forEach { (date, items) ->

                                // 📅 DATE HEADER
                                Text(
                                    text = date,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                // 🔔 NOTIFICATIONS UNDER DATE
                                items
                                    .sortedByDescending {
                                        it.child("time").getValue(Long::class.java) ?: 0L
                                    }
                                    .forEach { snap ->

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = MaterialTheme.shapes.medium
                                                )
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = snap.child("title").value.toString(),
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = snap.child("message").value.toString()
                                            )
                                        }
                                    }
                            }
                    }
                }
            }
        }



        /* ================= UI ================= */

        Box(modifier = Modifier.fillMaxSize()) {

            // 1️⃣ MAP
            if (started) {
                BusMap(
                    lat = lat,
                    lng = lng,
                    tripMode = tripMode,
                    homeLat = homeLat,
                    homeLng = homeLng,
                    userType = "STUDENT",
                    onEtaUpdate = {}
                )
            } else {
                CenterText("🚌 Driver not started")
            }

            // 2️⃣ 🔔 Bell icon (TOP RIGHT)
            IconButton(
                onClick = { showNotifications = true },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Notifications"
                )
            }

            // 3️⃣ 📊 INFO CARD  ✅ (THIS IS WHAT YOU ASKED ABOUT)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp) // 👈 important so it doesn’t overlap bell
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = if (tripMode == "MORNING")
                        "🚌 ETA to School: $eta min"
                    else
                        "🏠 ETA to Home: $eta min",
                    style = MaterialTheme.typography.bodyLarge
                )

                Text(
                    text = "🚍 Speed: ${"%.1f".format(speed)} km/h",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "📍 Distance: ${"%.2f".format(distance)} km",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "⏱ Last update: $lastUpdate",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 4️⃣ 🔔 Notification screen (overlay)
            if (showNotifications) {
                StudentNotificationsScreen(
                    busId = busId,
                    regNo = regNo,
                    onClose = { showNotifications = false }
                )
            }


            // 5️⃣ 🚪 LOGOUT BUTTON
            Button(
                onClick = onLogout,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Text("Logout")
            }
        }

    }

    /* ================= MAP (SINGLE, FIXED) ================= */
    @Composable
    fun BusMap(
        lat: Double,
        lng: Double,
        tripMode: String,
        homeLat: Double?,
        homeLng: Double?,
        userType: String,          // 👈 ADD THIS
        onEtaUpdate: (Int) -> Unit
    )
     {
        val context = LocalContext.current

        /* ================= MAP VIEW ================= */

        val mapView = remember {
            MapView(context).apply {
                onCreate(Bundle())
                onResume()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                mapView.onPause()
                mapView.onDestroy()
            }
        }

        var googleMap by remember { mutableStateOf<GoogleMap?>(null) }

        AndroidView(factory = { mapView }) { view ->
            view.getMapAsync { map ->
                googleMap = map
                map.uiSettings.isZoomControlsEnabled = true
                map.uiSettings.isMyLocationButtonEnabled = false
            }
        }

        /* ================= STATE ================= */

        var busMarker by remember { mutableStateOf<Marker?>(null) }
        var lastBusPos by remember { mutableStateOf<LatLng?>(null) }

        var destinationMarker by remember { mutableStateOf<Marker?>(null) }
        var lastDestination by remember { mutableStateOf<LatLng?>(null) }

        var routeLine by remember { mutableStateOf<Polyline?>(null) }

        /* =========================================================
           🟢 EFFECT 1 — REAL-TIME BUS MOVEMENT (FIREBASE UPDATES)
           ========================================================= */

        LaunchedEffect(lat, lng) {
            val map = googleMap ?: return@LaunchedEffect
            if (lat == 0.0 || lng == 0.0) return@LaunchedEffect

            val newPos = LatLng(lat, lng)
            if (userType == "DRIVER") {
                val camera = CameraPosition.Builder()
                    .target(LatLng(lat, lng))   // 🎯 only bus
                    .zoom(17f)                  // close zoom
                    .tilt(45f)
                    .build()

                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(camera)
                )
            }

            if (busMarker == null) {
                busMarker = map.addMarker(
                    MarkerOptions()
                        .position(newPos)
                        .icon(
                            BitmapDescriptorFactory.fromResource(
                                R.drawable.bus_icon
                            )
                        )
                        .anchor(0.5f, 0.5f)
                        .flat(true)
                )

                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(newPos, 16f)
                )

                lastBusPos = newPos
            } else {
                val start = lastBusPos ?: newPos
                animateMarker(busMarker!!, start, newPos)
                lastBusPos = newPos
            }
        }

        /* =========================================================
           🟢 EFFECT 2 — ROUTE + CAMERA (ONLY WHEN MODE / DEST CHANGES)
           ========================================================= */

        LaunchedEffect(tripMode, homeLat, homeLng) {
            val map = googleMap ?: return@LaunchedEffect
            if (lat == 0.0 || lng == 0.0) return@LaunchedEffect
            if (userType == "STUDENT") {

                val origin = LatLng(lat, lng)

                val destination =
                    if (tripMode == "MORNING") {
                        LatLng(SCHOOL_LAT, SCHOOL_LNG)
                    } else if (homeLat != null && homeLng != null) {
                        LatLng(homeLat, homeLng)
                    } else {
                        LatLng(SCHOOL_LAT, SCHOOL_LNG)
                    }

                val bounds = LatLngBounds.builder()
                    .include(origin)
                    .include(destination)
                    .build()

                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(bounds, 180)
                )
            }

            val origin = LatLng(lat, lng)

            val destination =
                if (tripMode == "MORNING") {
                    LatLng(SCHOOL_LAT, SCHOOL_LNG)
                } else if (homeLat != null && homeLng != null) {
                    LatLng(homeLat, homeLng)
                } else {
                    LatLng(SCHOOL_LAT, SCHOOL_LNG)
                }

            // 🚫 Prevent re-running for same destination
            if (lastDestination == destination) return@LaunchedEffect
            lastDestination = destination

            /* ---------- DESTINATION MARKER ---------- */

            if (destinationMarker == null) {
                destinationMarker = map.addMarker(
                    MarkerOptions()
                        .position(destination)
                        .title(
                            if (tripMode == "MORNING") "School" else "Home"
                        )
                        .icon(
                            BitmapDescriptorFactory.defaultMarker(
                                BitmapDescriptorFactory.HUE_RED
                            )
                        )
                )
            } else {
                destinationMarker!!.position = destination
            }

            /* ---------- CAMERA FIT ---------- */

            val cameraPosition = CameraPosition.Builder()
                .target(LatLng(lat, lng)) // 🎯 bus location
                .zoom(17f)                // 🔍 close zoom (adjust if needed)
                .bearing(busMarker?.rotation ?: 0f) // optional: direction
                .tilt(45f)                // optional: 3D look
                .build()

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(cameraPosition)
            )


            /* ---------- GOOGLE DIRECTIONS API ---------- */

            val apiKey =
                context.packageManager
                    .getApplicationInfo(
                        context.packageName,
                        PackageManager.GET_META_DATA
                    )
                    .metaData
                    .getString("com.google.android.geo.API_KEY")

            val url =
                "https://maps.googleapis.com/maps/api/directions/json?" +
                        "origin=${origin.latitude},${origin.longitude}" +
                        "&destination=${destination.latitude},${destination.longitude}" +
                        "&mode=driving" +
                        "&departure_time=now" +
                        "&key=$apiKey"

            OkHttpClient().newCall(
                Request.Builder().url(url).build()
            ).enqueue(object : Callback {

                override fun onFailure(call: Call, e: IOException) {}

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return
                    val json = JSONObject(body)

                    val routes = json.getJSONArray("routes")
                    if (routes.length() == 0) return

                    val leg =
                        routes.getJSONObject(0)
                            .getJSONArray("legs")
                            .getJSONObject(0)

                    val durationSec =
                        if (leg.has("duration_in_traffic")) {
                            leg.getJSONObject("duration_in_traffic").getInt("value")
                        } else {
                            leg.getJSONObject("duration").getInt("value")
                        }

                    val etaMin =
                        Math.ceil(durationSec / 60.0).toInt()

                    val polyline =
                        routes.getJSONObject(0)
                            .getJSONObject("overview_polyline")
                            .getString("points")

                    val points = decodePolyline(polyline)

                    (context as ComponentActivity).runOnUiThread {

                        onEtaUpdate(etaMin)

                        routeLine?.remove()
                        routeLine = map.addPolyline(
                            PolylineOptions()
                                .addAll(points)
                                .color(android.graphics.Color.BLUE)
                                .width(9f)
                        )
                    }
                }
            })
        }
    }

    // ================= ETA & DISTANCE HELPERS =================

    fun distanceInKm(a: LatLng, b: LatLng): Double {
        val R = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)

        val sa =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(a.latitude)) *
                    Math.cos(Math.toRadians(b.latitude)) *
                    Math.sin(dLng / 2) * Math.sin(dLng / 2)

        val c = 2 * Math.atan2(Math.sqrt(sa), Math.sqrt(1 - sa))
        return R * c
    }

    fun etaMinutes(distanceKm: Double, speedKmph: Double = 30.0): Int {
        if (distanceKm <= 0) return 0
        return Math.ceil((distanceKm / speedKmph) * 60).toInt()
    }

    /* ================= LOCATION ================= */

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                res.lastLocation?.let {
                    FirebaseDatabase.getInstance()
                        .getReference("buses/${prefs().getString("busId", "")}")
                        .setValue(mapOf("lat" to it.latitude, "lng" to it.longitude))
                }
            }
        }
        fusedClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
    }

    /* ================= BACKEND (UNCHANGED) ================= */

    private fun postToBackend(path: String, busId: String, type: String? = null) {
        val json = JSONObject().put("busId", busId)
        if (type != null) json.put("type", type)

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        OkHttpClient().newCall(
            Request.Builder()
                .url("$BASE_URL$path")
                .post(body)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    private fun verifyDriver(phone: String, onSuccess: (String) -> Unit) {
        val body = JSONObject().put("phone", phone)
            .toString().toRequestBody("application/json".toMediaType())

        OkHttpClient().newCall(
            Request.Builder()
                .url("$BASE_URL/drivers/check-driver")
                .post(body)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return
                val busId =
                    JSONObject(response.body!!.string())
                        .getJSONObject("driver")
                        .getString("busId")
                runOnUiThread { onSuccess(busId) }
            }
        })
    }

    private fun verifyStudent(reg: String, phone: String, onSuccess: (String) -> Unit) {
        val body = JSONObject()
            .put("regNo", reg)
            .put("phone", phone)
            .toString().toRequestBody("application/json".toMediaType())

        OkHttpClient().newCall(
            Request.Builder()
                .url("$BASE_URL/students/check-student")
                .post(body)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return
                val busId =
                    JSONObject(response.body!!.string())
                        .getJSONObject("student")
                        .getString("busId")
                runOnUiThread { onSuccess(busId) }
            }
        })
    }
}