package com.example.seleneweather

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.seleneweather.ui.theme.SeleneWeatherTheme
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.sin

// ============================================================================
// DATA STORE (City Persistence & Favorites)
// ============================================================================

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class CityPreference(private val context: Context) {
    companion object {
        val LOCATION_ID_KEY = stringPreferencesKey("location_id_v6")
        val FAVORITES_KEY = stringSetPreferencesKey("favorites_list_v6")
        const val DEFAULT_LOCATION = "London|England|United Kingdom|51.50853|-0.12574"
    }

    val locationIdFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LOCATION_ID_KEY] ?: DEFAULT_LOCATION
    }

    val favoritesFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[FAVORITES_KEY] ?: emptySet()
    }

    suspend fun saveLocation(locationId: String) {
        context.dataStore.edit { preferences ->
            preferences[LOCATION_ID_KEY] = locationId
        }
    }

    suspend fun toggleFavorite(locationId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[FAVORITES_KEY] ?: emptySet()
            if (current.contains(locationId)) {
                preferences[FAVORITES_KEY] = current - locationId
            } else {
                preferences[FAVORITES_KEY] = current + locationId
            }
        }
    }
}

// ============================================================================
// WEATHER API MODELS & SERVICE
// ============================================================================

data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val current: Current,
    val hourly: Hourly,
    val daily: Daily
)

data class Current(
    val time: String,
    val temperature_2m: Double,
    val apparent_temperature: Double,
    val weather_code: Int,
    val relative_humidity_2m: Double,
    val wind_speed_10m: Double,
    val surface_pressure: Double,
    val is_day: Int,
    val uv_index: Double? = null,
    val visibility: Double? = null
)

data class Hourly(
    val time: List<String>,
    val temperature_2m: List<Double>,
    val weather_code: List<Int>,
    val relative_humidity_2m: List<Double>? = null,
    val wind_speed_10m: List<Double>? = null,
    val is_day: List<Int>? = null
)

data class Daily(
    val time: List<String>,
    val weather_code: List<Int>,
    val temperature_2m_max: List<Double>,
    val temperature_2m_min: List<Double>,
    val sunrise: List<String>,
    val sunset: List<String>,
    val uv_index_max: List<Double>? = null
)

data class GeocodeResponse(
    val results: List<GeocodeResult>?
)

data class GeocodeResult(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val country: String? = null,
    val admin1: String? = null
) {
    val uniqueId: String get() = "$name|${admin1 ?: ""}|${country ?: ""}|$latitude|$longitude"
}

data class HourlyForecast(
    val time: String,
    val temp: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val humidity: Int? = null,
    val wind: Double? = null
)

data class DailyForecast(
    val date: String,
    val weatherCode: Int,
    val maxTemp: Double,
    val minTemp: Double,
    val dayOfWeek: String
)

interface WeatherApi {
    @GET("v1/forecast")
    suspend fun getWeather(
        @Query("latitude") latitude: String,
        @Query("longitude") longitude: String,
        @Query("current") current: String,
        @Query("hourly") hourly: String,
        @Query("daily") daily: String,
        @Query("timezone") timezone: String,
        @Query("forecast_days") forecastDays: Int
    ): OpenMeteoResponse

    @GET("v1/search")
    suspend fun geocode(
        @Query("name") city: String,
        @Query("count") count: Int
    ): GeocodeResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://api.open-meteo.com/"
    private const val GEO_URL = "https://geocoding-api.open-meteo.com/"

    val weatherApi: WeatherApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }

    val geocodeApi: WeatherApi by lazy {
        Retrofit.Builder()
            .baseUrl(GEO_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }
}

// ============================================================================
// VIEW MODEL
// ============================================================================

sealed class WeatherUiState {
    object Loading : WeatherUiState()
    data class Success(
        val city: String,
        val currentTemp: Double,
        val feelsLike: Double,
        val high: Double,
        val low: Double,
        val condition: String,
        val hourly: List<HourlyForecast>,
        val daily: List<DailyForecast>,
        val humidity: Double,
        val windSpeed: Double,
        val pressure: Double,
        val isDay: Boolean,
        val localTime: String,
        val lastUpdated: String,
        val uvIndex: Double,
        val visibility: Double,
        val sunrise: String,
        val sunset: String
    ) : WeatherUiState()
    data class Error(val message: String) : WeatherUiState()
}

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    private val cityPreference = CityPreference(application)
    private val api = RetrofitClient.weatherApi
    private val geoApi = RetrofitClient.geocodeApi
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    private val _uiState = MutableStateFlow<WeatherUiState>(WeatherUiState.Loading)
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<GeocodeResult>>(emptyList())
    val searchSuggestions: StateFlow<List<GeocodeResult>> = _searchSuggestions.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _currentLocationName = MutableStateFlow("Loading...")
    val currentLocationName: StateFlow<String> = _currentLocationName.asStateFlow()

    private val _isLocating = MutableStateFlow(false)
    val isLocating: StateFlow<Boolean> = _isLocating.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            cityPreference.favoritesFlow.collect { _favorites.value = it }
        }
        detectAndLoadLocation()
    }

    private fun detectAndLoadLocation() {
        if (hasLocationPermission() && isLocationEnabled()) {
            fetchCurrentLocationWeather()
        } else {
            loadWeather()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationEnabled(): Boolean {
        val context = getApplication<Application>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun showLocationDisabledError() {
        _uiState.value = WeatherUiState.Error("Location services are off. Please enable them in settings to use your current location.")
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocationWeather() {
        viewModelScope.launch {
            _isLocating.value = true
            _uiState.value = WeatherUiState.Loading
            try {
                if (!isLocationEnabled()) {
                    showLocationDisabledError()
                    return@launch
                }

                val location = getLocation()
                if (location != null) {
                    val cityName = getCityNameFromCoordinates(location.latitude, location.longitude)
                    updateWeatherByCoordinates(location.latitude, location.longitude, cityName)
                } else {
                    _uiState.value = WeatherUiState.Error("Unable to fetch your current location. Please try again.")
                }
            } catch (e: Exception) {
                _uiState.value = WeatherUiState.Error("Error: ${e.localizedMessage ?: "Failed to get location"}")
            } finally {
                _isLocating.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLocation(): Location? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        
        // 1. Try Google Play Services (Fused Location) if available
        if (isGooglePlayServicesAvailable(context)) {
            try {
                val cancellationTokenSource = CancellationTokenSource()
                val gmsLocation = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cancellationTokenSource.token
                ).await()
                if (gmsLocation != null) return@withContext gmsLocation
            } catch (e: Exception) {
                // Fallback to LocationManager
            }
        }

        // 2. Fallback to Android System LocationManager (Non-GMS compliant)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Try getting fresh location on API 30+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val location = suspendCancellableCoroutine<Location?> { continuation ->
                    locationManager.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        null,
                        ContextCompat.getMainExecutor(context),
                        { continuation.resume(it) }
                    )
                }
                if (location != null) return@withContext location
            } catch (e: Exception) {}
        }

        // Try last known location
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        bestLocation
    }

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }

    private suspend fun getCityNameFromCoordinates(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(getApplication(), Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val addresses = suspendCancellableCoroutine<List<android.location.Address>?> { continuation ->
                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                        continuation.resume(addresses)
                    }
                }
                addresses?.firstOrNull()?.let {
                    return@withContext it.locality ?: it.subAdminArea ?: it.adminArea ?: it.countryName ?: "Current Location"
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                addresses?.firstOrNull()?.let {
                    return@withContext it.locality ?: it.subAdminArea ?: it.adminArea ?: it.countryName ?: "Current Location"
                }
            }
            "Current Location"
        } catch (e: Exception) {
            "Current Location"
        }
    }

    private fun updateWeatherByCoordinates(lat: Double, lon: Double, displayName: String) {
        viewModelScope.launch {
            _currentLocationName.value = displayName
            fetchWeather(lat.toString(), lon.toString(), displayName)
        }
    }

    fun loadWeather() {
        viewModelScope.launch {
            _uiState.value = WeatherUiState.Loading
            try {
                val locationId = cityPreference.locationIdFlow.first()
                val parts = locationId.split("|")
                
                val lat: String
                val lon: String
                val name: String

                if (parts.size >= 5) {
                    name = parts[0]
                    lat = parts[3]
                    lon = parts[4]
                } else {
                    val geoResult = geoApi.geocode("London", 1).results?.firstOrNull()
                        ?: throw Exception("Critical Error: Default location failed.")
                    name = geoResult.name
                    lat = geoResult.latitude.toString()
                    lon = geoResult.longitude.toString()
                    cityPreference.saveLocation(geoResult.uniqueId)
                }

                _currentLocationName.value = name
                fetchWeather(lat, lon, name)
            } catch (e: Exception) {
                _uiState.value = WeatherUiState.Error(e.message ?: "Failed to load weather data.")
            }
        }
    }

    private suspend fun fetchWeather(lat: String, lon: String, name: String) {
        try {
            val weather = api.getWeather(
                latitude = lat,
                longitude = lon,
                current = "temperature_2m,apparent_temperature,weather_code,relative_humidity_2m,wind_speed_10m,surface_pressure,is_day,uv_index,visibility",
                hourly = "temperature_2m,weather_code,is_day,relative_humidity_2m,wind_speed_10m",
                daily = "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max",
                timezone = "auto",
                forecastDays = 7
            )

            val hourlyForecasts = weather.hourly.time.take(24).mapIndexed { index, time ->
                HourlyForecast(
                    time = if (time.length >= 16) time.substring(11, 16) else time,
                    temp = weather.hourly.temperature_2m.getOrElse(index) { 0.0 },
                    weatherCode = weather.hourly.weather_code.getOrElse(index) { 0 },
                    isDay = weather.hourly.is_day?.getOrElse(index) { 1 } == 1,
                    humidity = weather.hourly.relative_humidity_2m?.getOrNull(index)?.toInt(),
                    wind = weather.hourly.wind_speed_10m?.getOrNull(index)
                )
            }

            val dailyForecasts = weather.daily.time.mapIndexed { index, time ->
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(time) ?: Date()
                val dayOfWeek = SimpleDateFormat("EEE", Locale.getDefault()).format(date)
                DailyForecast(
                    date = time,
                    weatherCode = weather.daily.weather_code[index],
                    maxTemp = weather.daily.temperature_2m_max[index],
                    minTemp = weather.daily.temperature_2m_min[index],
                    dayOfWeek = if (index == 0) "Today" else dayOfWeek
                )
            }

            val high = weather.daily.temperature_2m_max.firstOrNull() ?: weather.current.temperature_2m
            val low = weather.daily.temperature_2m_min.firstOrNull() ?: weather.current.temperature_2m

            val lastUpdatedTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            val sunriseTime = weather.daily.sunrise.firstOrNull()?.let { if (it.length >= 16) it.substring(11, 16) else it } ?: "--:--"
            val sunsetTime = weather.daily.sunset.firstOrNull()?.let { if (it.length >= 16) it.substring(11, 16) else it } ?: "--:--"

            _uiState.value = WeatherUiState.Success(
                city = name,
                currentTemp = weather.current.temperature_2m,
                feelsLike = weather.current.apparent_temperature,
                high = high,
                low = low,
                condition = getWeatherCondition(weather.current.weather_code),
                hourly = hourlyForecasts,
                daily = dailyForecasts,
                humidity = weather.current.relative_humidity_2m,
                windSpeed = weather.current.wind_speed_10m,
                pressure = weather.current.surface_pressure,
                isDay = weather.current.is_day == 1,
                localTime = weather.current.time,
                lastUpdated = "Last updated at $lastUpdatedTime",
                uvIndex = weather.current.uv_index ?: 0.0,
                visibility = (weather.current.visibility ?: 0.0) / 1000.0, // convert to km
                sunrise = sunriseTime,
                sunset = sunsetTime
            )
        } catch (e: Exception) {
            _uiState.value = WeatherUiState.Error(e.message ?: "Failed to fetch weather data.")
        }
    }

    fun searchCity(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _searchSuggestions.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            try {
                val results = geoApi.geocode(query, 10).results ?: emptyList()
                _searchSuggestions.value = results
            } catch (e: Exception) {
                _searchSuggestions.value = emptyList()
            }
        }
    }

    fun updateCity(locationId: String) {
        viewModelScope.launch {
            val name = locationId.split("|").firstOrNull() ?: "Loading..."
            _currentLocationName.value = name
            cityPreference.saveLocation(locationId)
            _searchSuggestions.value = emptyList()
            loadWeather()
        }
    }

    fun toggleFavorite(locationId: String) {
        viewModelScope.launch {
            cityPreference.toggleFavorite(locationId)
        }
    }

    fun resetToDefault() {
        updateCity(CityPreference.DEFAULT_LOCATION)
    }

    private fun getWeatherCondition(code: Int): String {
        return when (code) {
            0 -> "Clear Sky"
            1, 2, 3 -> "Partly Cloudy"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rainy"
            71, 73, 75 -> "Snow"
            95, 96, 99 -> "Thunderstorm"
            else -> "Unknown"
        }
    }
}

// ============================================================================
// ANIMATED BACKGROUNDS
// ============================================================================

@Composable
fun WeatherBackground(condition: String, isDay: Boolean, localTime: String) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = MaterialTheme.colorScheme.background
    
    val primary = if (isDark) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
    val secondary = if (isDark) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.secondary

    val horizonProgress = remember(localTime, isDay) {
        try {
            val timePart = localTime.substringAfter('T', "")
            if (timePart.contains(':')) {
                val hour = timePart.substringBefore(':').toInt()
                val minute = timePart.substringAfter(':').toInt()
                val totalMinutes = hour * 60 + minute
                if (isDay) {
                    ((totalMinutes - 360).toFloat() / 720f).coerceIn(0f, 1f)
                } else {
                    if (totalMinutes >= 1080) ((totalMinutes - 1080).toFloat() / 720f).coerceIn(0f, 1f)
                    else ((totalMinutes + 1440 - 1080).toFloat() / 720f).coerceIn(0f, 1f)
                }
            } else 0.5f
        } catch (e: Exception) { 0.5f }
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        CelestialBody(
            color = if (isDay) Color(0xFFFF9800) else Color(0xFF9FA8DA),
            progress = horizonProgress
        )
        when (condition) {
            "Partly Cloudy", "Foggy" -> ModernBlurryClouds(secondary)
            "Rainy", "Drizzle" -> ModernBlurryRain(primary)
            "Snow" -> ModernBlurrySnow()
            "Thunderstorm" -> ModernBlurryStorm(primary)
            else -> {}
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, backgroundColor.copy(alpha = 0.2f), backgroundColor.copy(alpha = 0.7f))
                    )
                )
        )
    }
}

@Composable
fun CelestialBody(color: Color, progress: Float) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "celestial")
    val pulse by infiniteTransition.animateFloat(1f, 1.02f, animationSpec = infiniteRepeatable(tween(10000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val animatedProgress by animateFloatAsState(progress, animationSpec = tween(3000, easing = EaseInOutCubic), label = "horizon")

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val size = 180.dp
        val centerOffset = -(size / 2) 
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = (animatedProgress - 0.5f) * this.size.width * 0.8f }, 
            contentAlignment = Alignment.TopCenter
        ) {
            repeat(2) { i ->
                val rippleProgress by infiniteTransition.animateFloat(0f, 1f, animationSpec = infiniteRepeatable(tween(12000, delayMillis = i * 6000, easing = LinearEasing)), label = "ripple")
                Box(modifier = Modifier.offset(y = centerOffset).size(size + (200.dp * rippleProgress)).alpha(0.7f * (1f - rippleProgress)).background(color.copy(alpha = if (isDark) 0.3f else 0.45f), CircleShape).blur(15.dp))
            }
            Box(modifier = Modifier.offset(y = centerOffset).size(size).graphicsLayer { scaleX = pulse; scaleY = pulse }.background(color.copy(alpha = if (isDark) 0.7f else 0.9f), CircleShape).blur(20.dp))
        }
    }
}

@Composable
fun ModernBlurryClouds(color: Color) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "clouds")
    Box(modifier = Modifier.fillMaxSize()) {
        repeat(2) { i ->
            val duration = if (i == 0) 100000 else 140000
            val xPos by infiniteTransition.animateFloat(-0.5f, 1.5f, animationSpec = infiniteRepeatable(tween(duration, delayMillis = if (i == 0) 0 else 50000, easing = LinearEasing)), label = "x")
            val yOffsetProgress by infiniteTransition.animateFloat(0f, 1f, animationSpec = infiniteRepeatable(tween(20000 + i * 10000, easing = LinearEasing), RepeatMode.Reverse), label = "y")
            val yBase = if (i == 0) 160.dp else 220.dp
            val yDrift = (sin(yOffsetProgress * PI.toFloat() * 2) * 15).dp
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { translationX = xPos * size.width; translationY = yBase.toPx() + yDrift.toPx() }) {
                val baseSize = if (i == 0) 180.dp else 140.dp
                val alphaValue = if (isDark) 0.45f else 0.65f 
                repeat(2) { waveIdx ->
                    val waveProgress by infiniteTransition.animateFloat(0f, 1f, animationSpec = infiniteRepeatable(tween(15000, delayMillis = waveIdx * 7500, easing = LinearEasing)), label = "wave")
                    Box(modifier = Modifier.offset(x = baseSize * 0.2f, y = 10.dp).size(baseSize * (1.2f + 0.5f * waveProgress)).alpha(0.5f * (1f - waveProgress)).background(color.copy(alpha = alphaValue * 0.4f), CircleShape).blur(30.dp))
                }
                Box(modifier = Modifier.offset(x = 0.dp, y = 15.dp).size(baseSize).background(color.copy(alpha = alphaValue), CircleShape).blur(40.dp))
                Box(modifier = Modifier.offset(x = baseSize * 0.4f, y = 0.dp).size(baseSize * 1.2f).background(color.copy(alpha = alphaValue), CircleShape).blur(40.dp))
                Box(modifier = Modifier.offset(x = baseSize * 0.9f, y = 20.dp).size(baseSize * 0.8f).background(color.copy(alpha = alphaValue), CircleShape).blur(40.dp))
            }
        }
    }
}

@Composable
fun ModernBlurryRain(color: Color) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "rain")
    val rainY = infiniteTransition.animateFloat(0f, 1200f, animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "rainY")
    Canvas(modifier = Modifier.fillMaxSize()) {
        repeat(30) { i -> 
            val startX = (i * 271.5f) % size.width
            val startY = (rainY.value + i * 220f) % size.height
            drawLine(color.copy(alpha = if (isDark) 0.35f else 0.6f), Offset(startX, startY), Offset(startX, startY + 28f), 2.5.dp.toPx())
        }
    }
}

@Composable
fun ModernBlurrySnow() {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "snow")
    val snowProgress = infiniteTransition.animateFloat(0f, 1f, animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "snow")
    val color = if (isDark) Color.White else MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        repeat(20) { i -> 
            val speed = 0.6f + (i % 3) * 0.1f
            val x = (i * 357.5f) % size.width
            val y = (snowProgress.value * size.height * speed + i * 350f) % size.height
            drawCircle(color.copy(alpha = if (isDark) 0.4f else 0.6f), 3.5.dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
fun ModernBlurryStorm(color: Color) {
    val flash by rememberInfiniteTransition(label = "storm").animateFloat(0f, 1f, animationSpec = infiniteRepeatable(keyframes { durationMillis = 15000; 0f at 0; 0f at 14000; 1f at 14050; 0f at 14150; 1f at 14200; 0f at 14400 }), label = "flash")
    Box(modifier = Modifier.fillMaxSize()) { 
        ModernBlurryRain(color)
        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = flash * 0.05f))) 
    }
}

// ============================================================================
// UI COMPONENTS
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherApp(viewModel: WeatherViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val currentLocationName by viewModel.currentLocationName.collectAsState()
    val isLocating by viewModel.isLocating.collectAsState()
    var showCityDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            if (viewModel.isLocationEnabled()) {
                viewModel.fetchCurrentLocationWeather()
            } else {
                viewModel.showLocationDisabledError()
            }
        }
    }

    SeleneWeatherTheme {
        val currentCondition = (uiState as? WeatherUiState.Success)?.condition ?: "Unknown"
        val isDay = (uiState as? WeatherUiState.Success)?.isDay ?: true
        val localTime = (uiState as? WeatherUiState.Success)?.localTime ?: ""
        
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                WeatherBackground(condition = currentCondition, isDay = isDay, localTime = localTime)
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(), 
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                        CityHeader(city = currentLocationName, onClick = { showCityDialog = true })

                        val pullRefreshState = rememberPullToRefreshState()
                        PullToRefreshBox(
                            isRefreshing = uiState is WeatherUiState.Loading || isLocating,
                            onRefresh = { viewModel.loadWeather() },
                            state = pullRefreshState,
                            modifier = Modifier.weight(1f)
                        ) {
                            when (val state = uiState) {
                                is WeatherUiState.Loading -> LoadingScreen()
                                is WeatherUiState.Error -> ErrorScreen(
                                    message = state.message, 
                                    onRetry = { viewModel.loadWeather() },
                                    onReset = { viewModel.resetToDefault() }
                                )
                                is WeatherUiState.Success -> WeatherContent(weather = state)
                            }
                        }
                    }

                    if (showCityDialog) {
                        CityDialog(
                            suggestions = searchSuggestions,
                            favorites = favorites,
                            onSearch = { viewModel.searchCity(it) },
                            onDismiss = { showCityDialog = false; viewModel.searchCity("") },
                            onConfirm = { id -> viewModel.updateCity(id); showCityDialog = false },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onCurrentLocation = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    if (viewModel.isLocationEnabled()) {
                                        viewModel.fetchCurrentLocationWeather()
                                        showCityDialog = false
                                    } else {
                                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                        context.startActivity(intent)
                                        showCityDialog = false
                                    }
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen() { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp), 
        horizontalAlignment = Alignment.CenterHorizontally, 
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Warning, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        
        Button(onRetry, modifier = Modifier.padding(top = 32.dp), shape = CircleShape) {
            Text("Try Again")
        }
        
        TextButton(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) {
            Text("Reset to Default Location")
        }
    }
}

@Composable
fun WeatherContent(weather: WeatherUiState.Success) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(Modifier.height(16.dp))
        CurrentWeatherDisplay(weather)
        Spacer(Modifier.height(32.dp))
        
        WeatherDetailsGrid(weather)
        Spacer(Modifier.height(24.dp))
        
        HourlyForecastSection(weather.hourly)
        Spacer(Modifier.height(24.dp))
        
        DailyForecastSection(weather.daily)
        Spacer(Modifier.height(24.dp))
        
        AdditionalDetailsGrid(weather)
        
        Spacer(Modifier.height(20.dp))
        Text(weather.lastUpdated, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun CityHeader(city: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), tonalElevation = 2.dp) {
            Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(city, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant))
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun CurrentWeatherDisplay(weather: WeatherUiState.Success) {
    val textStyle = MaterialTheme.colorScheme.onBackground
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(weather.condition, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium, color = textStyle.copy(alpha = 0.9f)))
        Spacer(Modifier.height(12.dp))
        Text("${weather.currentTemp.toInt()}°", style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp, fontWeight = FontWeight.Thin, color = textStyle))
        Text("Feels like ${weather.feelsLike.toInt()}°", style = MaterialTheme.typography.titleMedium.copy(color = textStyle.copy(alpha = 0.8f)))
        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TemperaturePill("H", weather.high.toInt())
            TemperaturePill("L", weather.low.toInt())
        }
    }
}

@Composable
fun TemperaturePill(label: String, value: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Text("$label: $value°", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun WeatherDetailsGrid(weather: WeatherUiState.Success) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DetailItem(Icons.Rounded.WaterDrop, "Humidity", "${weather.humidity.toInt()}%")
            DetailItem(Icons.Rounded.Air, "Wind", "${weather.windSpeed.toInt()} km/h")
            DetailItem(Icons.Rounded.Compress, "Pressure", "${weather.pressure.toInt()} hPa")
        }
    }
}

@Composable
fun AdditionalDetailsGrid(weather: WeatherUiState.Success) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailCard(Modifier.weight(1f), Icons.Rounded.LightMode, "UV Index", String.format(Locale.getDefault(), "%.1f", weather.uvIndex))
            DetailCard(Modifier.weight(1f), Icons.Rounded.Visibility, "Visibility", "${weather.visibility.toInt()} km")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailCard(Modifier.weight(1f), Icons.Rounded.WbTwilight, "Sunrise", weather.sunrise)
            DetailCard(Modifier.weight(1f), Icons.Rounded.WbTwilight, "Sunset", weather.sunset)
        }
    }
}

@Composable
fun DetailCard(modifier: Modifier = Modifier, icon: ImageVector, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DetailItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun HourlyForecastSection(hourly: List<HourlyForecast>) {
    Column(Modifier.fillMaxWidth()) {
        Text("Hourly Forecast", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)) {
            LazyRow(modifier = Modifier.padding(vertical = 20.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(hourly) { hour ->
                    Column(modifier = Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(hour.time, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(getWeatherEmoji(hour.weatherCode, hour.isDay), fontSize = 28.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("${hour.temp.toInt()}°", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (hour.humidity != null) {
                            Text("${hour.humidity}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DailyForecastSection(daily: List<DailyForecast>) {
    Column(Modifier.fillMaxWidth()) {
        Text("7-Day Forecast", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)) {
            Column(modifier = Modifier.padding(16.dp)) {
                daily.forEachIndexed { index, forecast ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(forecast.dayOfWeek, modifier = Modifier.width(60.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(getWeatherEmoji(forecast.weatherCode, true), fontSize = 24.sp)
                        Row(modifier = Modifier.width(100.dp), horizontalArrangement = Arrangement.End) {
                            Text("${forecast.maxTemp.toInt()}°", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Text("${forecast.minTemp.toInt()}°", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    if (index < daily.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        }
    }
}

fun getWeatherEmoji(code: Int, isDay: Boolean = true): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1, 2, 3 -> if (isDay) "⛅" else "☁️"
    45, 48 -> "🌫️"
    51, 53, 55 -> "🌦️"
    61, 63, 65 -> "🌧️"
    71, 73, 75 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "☁️"
}

@Composable
fun CityDialog(
    suggestions: List<GeocodeResult>,
    favorites: Set<String>,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onCurrentLocation: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Locations", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                TextField(
                    value = text, 
                    onValueChange = { text = it; onSearch(it) }, 
                    placeholder = { Text("Search city...") }, 
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, 
                    trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; onSearch("") }) { Icon(Icons.Rounded.Close, null) } }, 
                    singleLine = true, 
                    shape = CircleShape, 
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), 
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(Modifier.height(8.dp))
                
                TextButton(
                    onClick = onCurrentLocation,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.MyLocation, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Use Current Location", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), 
                    shape = RoundedCornerShape(28.dp), 
                    tonalElevation = 6.dp, 
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    LazyColumn(modifier = Modifier.padding(vertical = 8.dp)) {
                        if (text.isEmpty()) {
                            if (favorites.isNotEmpty()) {
                                item { 
                                    Text("Favorites", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) 
                                }
                                items(favorites.toList()) { id ->
                                    val parts = id.split("|")
                                    val name = parts.getOrNull(0) ?: id
                                    val sub = parts.getOrNull(2) ?: ""
                                    ListItem(
                                        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) }, 
                                        supportingContent = { if (sub.isNotEmpty()) Text(sub) }, 
                                        leadingContent = { Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFD700)) }, 
                                        trailingContent = { IconButton(onClick = { onToggleFavorite(id) }) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)) } }, 
                                        modifier = Modifier.clickable { onConfirm(id) }, 
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )
                                }
                            } else { 
                                item { 
                                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { 
                                        Text("No favorites yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) 
                                    } 
                                } 
                            }
                        } else {
                            if (suggestions.isNotEmpty()) {
                                items(suggestions) { result ->
                                    val isFav = favorites.contains(result.uniqueId)
                                    ListItem(
                                        headlineContent = { Text(result.name, fontWeight = FontWeight.Bold) }, 
                                        supportingContent = { Text("${result.admin1 ?: ""}, ${result.country ?: ""}") }, 
                                        trailingContent = { 
                                            IconButton(onClick = { onToggleFavorite(result.uniqueId) }) { 
                                                Icon(if (isFav) Icons.Rounded.Star else Icons.Rounded.StarOutline, null, tint = if (isFav) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant) 
                                            } 
                                        }, 
                                        modifier = Modifier.clickable { onConfirm(result.uniqueId) }, 
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )
                                }
                            } else { 
                                item { 
                                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { 
                                        Text("Searching...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) 
                                    } 
                                } 
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp)
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { WeatherApp() }
    }
}

suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: Exception("Task failed"))
        }
    }
}
