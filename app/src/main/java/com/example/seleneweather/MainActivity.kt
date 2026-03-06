package com.example.seleneweather

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.seleneweather.ui.theme.SeleneWeatherTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// ============================================================================
// DATA STORE (City Persistence)
// ============================================================================

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class CityPreference(private val context: Context) {
    companion object {
        val CITY_KEY = stringPreferencesKey("city_name")
        const val DEFAULT_CITY = "London"
    }

    val cityFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[CITY_KEY] ?: DEFAULT_CITY
    }

    suspend fun saveCity(city: String) {
        context.dataStore.edit { preferences ->
            preferences[CITY_KEY] = city
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
    val hourly: Hourly
)

data class Current(
    val time: String,
    val temperature_2m: Double,
    val apparent_temperature: Double,
    val weather_code: Int,
    val relative_humidity_2m: Double,
    val wind_speed_10m: Double,
    val surface_pressure: Double,
    val is_day: Int
)

data class Hourly(
    val time: List<String>,
    val temperature_2m: List<Double>,
    val weather_code: List<Int>
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
)

data class HourlyForecast(
    val time: String,
    val temp: Double,
    val weatherCode: Int
)

interface WeatherApi {
    @GET("v1/forecast")
    suspend fun getWeather(
        @Query("latitude") latitude: String,
        @Query("longitude") longitude: String,
        @Query("current") current: String,
        @Query("hourly") hourly: String,
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
        val humidity: Double,
        val windSpeed: Double,
        val pressure: Double,
        val isDay: Boolean,
        val localTime: String
    ) : WeatherUiState()
    data class Error(val message: String) : WeatherUiState()
}

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    private val cityPreference = CityPreference(application)
    private val api = RetrofitClient.weatherApi
    private val geoApi = RetrofitClient.geocodeApi

    private val _uiState = MutableStateFlow<WeatherUiState>(WeatherUiState.Loading)
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<GeocodeResult>>(emptyList())
    val searchSuggestions: StateFlow<List<GeocodeResult>> = _searchSuggestions.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadWeather()
    }

    fun loadWeather() {
        viewModelScope.launch {
            _uiState.value = WeatherUiState.Loading
            try {
                val city = cityPreference.cityFlow.first()
                val geoResult = geoApi.geocode(city, 10).results?.firstOrNull()
                    ?: throw Exception("Location not found: $city")

                val weather = api.getWeather(
                    latitude = String.format(Locale.US, "%.6f", geoResult.latitude),
                    longitude = String.format(Locale.US, "%.6f", geoResult.longitude),
                    current = "temperature_2m,apparent_temperature,weather_code,relative_humidity_2m,wind_speed_10m,surface_pressure,is_day",
                    hourly = "temperature_2m,weather_code",
                    timezone = "auto",
                    forecastDays = 2
                )

                val hourlyForecasts = weather.hourly.time.take(24).mapIndexed { index, time ->
                    HourlyForecast(
                        time = if (time.length >= 16) time.substring(11, 16) else time,
                        temp = weather.hourly.temperature_2m.getOrElse(index) { 0.0 },
                        weatherCode = weather.hourly.weather_code.getOrElse(index) { 0 }
                    )
                }

                val todayTemps = weather.hourly.temperature_2m.take(24)
                val high = if (todayTemps.isNotEmpty()) todayTemps.maxOrNull() ?: weather.current.temperature_2m else weather.current.temperature_2m
                val low = if (todayTemps.isNotEmpty()) todayTemps.minOrNull() ?: weather.current.temperature_2m else weather.current.temperature_2m

                _uiState.value = WeatherUiState.Success(
                    city = geoResult.name,
                    currentTemp = weather.current.temperature_2m,
                    feelsLike = weather.current.apparent_temperature,
                    high = high,
                    low = low,
                    condition = getWeatherCondition(weather.current.weather_code),
                    hourly = hourlyForecasts,
                    humidity = weather.current.relative_humidity_2m,
                    windSpeed = weather.current.wind_speed_10m,
                    pressure = weather.current.surface_pressure,
                    isDay = weather.current.is_day == 1,
                    localTime = weather.current.time
                )
            } catch (e: Exception) {
                _uiState.value = WeatherUiState.Error(e.message ?: "Network error. Please try again.")
            }
        }
    }

    fun searchCity(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _searchSuggestions.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // Debounce
            try {
                val results = geoApi.geocode(query, 10).results ?: emptyList()
                _searchSuggestions.value = results
            } catch (e: Exception) {
                _searchSuggestions.value = emptyList()
            }
        }
    }

    fun updateCity(city: String) {
        viewModelScope.launch {
            cityPreference.saveCity(city)
            _searchSuggestions.value = emptyList()
            loadWeather()
        }
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
// ANIMATED BACKGROUNDS (Optimized Modern Simple Blurry)
// ============================================================================

@Composable
fun WeatherBackground(condition: String, isDay: Boolean, localTime: String) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = MaterialTheme.colorScheme.background
    
    // Choose colors that are visible in both light and dark modes
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
                    // Map 6 AM (360) to 6 PM (1080)
                    ((totalMinutes - 360).toFloat() / 720f).coerceIn(0f, 1f)
                } else {
                    // Map 6 PM (1080) to 6 AM (360)
                    if (totalMinutes >= 1080) {
                        ((totalMinutes - 1080).toFloat() / 720f).coerceIn(0f, 1f)
                    } else {
                        ((totalMinutes + 1440 - 1080).toFloat() / 720f).coerceIn(0f, 1f)
                    }
                }
            } else 0.5f
        } catch (e: Exception) {
            0.5f
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        // Celestial Body Background - positioned at top horizon, moves based on time
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
        
        // Gradient overlay for better UI contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            backgroundColor.copy(alpha = 0.2f),
                            backgroundColor.copy(alpha = 0.7f)
                        )
                    )
                )
        )
    }
}

@Composable
fun CelestialBody(color: Color, progress: Float) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "celestial")
    
    // Core pulse - very subtle
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Smooth movement transition between locations or time updates
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 3000, easing = EaseInOutCubic),
        label = "horizon_move"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val size = 180.dp
        val centerOffset = -(size / 2) 

        // Wrap the celestial elements in a box that moves across the horizon
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Move across the horizon (roughly 80% of screen width)
                    translationX = (animatedProgress - 0.5f) * this.size.width * 0.8f
                },
            contentAlignment = Alignment.TopCenter
        ) {
            // Radiating light waves (Ripples)
            repeat(2) { i ->
                val rippleProgress by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(12000, delayMillis = i * 6000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ripple_$i"
                )
                
                Box(
                    modifier = Modifier
                        .offset(y = centerOffset)
                        .size(size + (200.dp * rippleProgress))
                        .alpha(0.2f * (1f - rippleProgress))
                        .background(color.copy(alpha = if (isDark) 0.1f else 0.2f), CircleShape)
                        .blur(15.dp)
                )
            }

            // Core Sun/Moon - Perfectly round solid shape cut by top edge
            Box(
                modifier = Modifier
                    .offset(y = centerOffset)
                    .size(size)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .background(color.copy(alpha = if (isDark) 0.4f else 0.6f), CircleShape)
                    .blur(20.dp)
            )
        }
    }
}

@Composable
fun ModernBlurryClouds(color: Color) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "clouds")

    Box(modifier = Modifier.fillMaxSize()) {
        repeat(2) { i ->
            // Use extremely long durations for very slow movement
            val duration = if (i == 0) 100000 else 140000
            val delay = if (i == 0) 0 else 50000
            
            val xPos by infiniteTransition.animateFloat(
                initialValue = -0.5f,
                targetValue = 1.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, delayMillis = delay, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "cloud_x_$i"
            )

            // Very subtle vertical drift using a separate animation
            val yOffsetProgress by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(20000 + i * 10000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "cloud_y_$i"
            )
            
            val yBase = if (i == 0) 160.dp else 220.dp
            val yDrift = (sin(yOffsetProgress * PI.toFloat() * 2) * 15).dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = xPos * size.width
                        translationY = yBase.toPx() + yDrift.toPx()
                    }
            ) {
                // Layered cloud: 3 overlapping blobs for a more "cloud-like" shape
                val baseSize = if (i == 0) 180.dp else 140.dp
                val alpha = if (isDark) 0.12f else 0.25f
                
                Box(
                    modifier = Modifier
                        .offset(x = 0.dp, y = 15.dp)
                        .size(baseSize)
                        .background(color.copy(alpha = alpha), CircleShape)
                        .blur(40.dp)
                )
                Box(
                    modifier = Modifier
                        .offset(x = baseSize * 0.4f, y = 0.dp)
                        .size(baseSize * 1.2f)
                        .background(color.copy(alpha = alpha), CircleShape)
                        .blur(40.dp)
                )
                Box(
                    modifier = Modifier
                        .offset(x = baseSize * 0.9f, y = 20.dp)
                        .size(baseSize * 0.8f)
                        .background(color.copy(alpha = alpha), CircleShape)
                        .blur(40.dp)
                )
            }
        }
    }
}

@Composable
fun ModernBlurryRain(color: Color) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "rain")
    val rainY = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rainY"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        repeat(20) { i -> 
            val startX = (i * 271.5f) % size.width
            val startY = (rainY.value + i * 220f) % size.height
            drawLine(
                color = color.copy(alpha = if (isDark) 0.25f else 0.5f),
                start = Offset(startX, startY),
                end = Offset(startX, startY + 18f),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Composable
fun ModernBlurrySnow() {
    val isDark = isSystemInDarkTheme()
    val primaryColor = MaterialTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "snow")
    val snowProgress = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "snowProgress"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        repeat(12) { i -> 
            val speedSeed = 0.6f + (i % 3) * 0.1f
            val x = (i * 357.5f) % size.width
            val y = (snowProgress.value * size.height * speedSeed + i * 350f) % size.height
            val color = if (isDark) Color.White else primaryColor
            drawCircle(
                color = color.copy(alpha = if (isDark) 0.3f else 0.5f),
                radius = 1.5.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

@Composable
fun ModernBlurryStorm(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "storm")
    val flash = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 15000 
                0f at 0
                0f at 14000
                1f at 14050
                0f at 14150
                1f at 14200
                0f at 14400
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "flash"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ModernBlurryRain(color)
        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value * 0.05f)))
    }
}

// ============================================================================
// UI COMPONENTS
// ============================================================================

@Composable
fun WeatherApp(viewModel: WeatherViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()
    var showCityDialog by remember { mutableStateOf(false) }

    SeleneWeatherTheme {
        val currentCondition = (uiState as? WeatherUiState.Success)?.condition ?: "Unknown"
        val isDay = (uiState as? WeatherUiState.Success)?.isDay ?: true
        val localTime = (uiState as? WeatherUiState.Success)?.localTime ?: ""
        
        Box(modifier = Modifier.fillMaxSize()) {
            // Background is drawn outside Scaffold or without padding to be edge-to-edge
            WeatherBackground(condition = currentCondition, isDay = isDay, localTime = localTime)
            
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    when (val state = uiState) {
                        is WeatherUiState.Loading -> LoadingScreen()
                        is WeatherUiState.Error -> ErrorScreen(
                            message = state.message,
                            onRetry = { viewModel.loadWeather() }
                        )
                        is WeatherUiState.Success -> WeatherContent(
                            weather = state,
                            onCityClick = { showCityDialog = true }
                        )
                    }

                    if (showCityDialog) {
                        CityDialog(
                            suggestions = searchSuggestions,
                            onSearch = { viewModel.searchCity(it) },
                            onDismiss = { 
                                showCityDialog = false
                                viewModel.searchCity("") 
                            },
                            onConfirm = { newCity ->
                                viewModel.updateCity(newCity)
                                showCityDialog = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherContent(
    weather: WeatherUiState.Success,
    onCityClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        CityHeader(
            city = weather.city,
            onClick = onCityClick
        )

        Spacer(modifier = Modifier.height(40.dp))
        
        CurrentWeatherDisplay(weather = weather)

        Spacer(modifier = Modifier.weight(1f))
        
        WeatherDetailsGrid(weather = weather)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        HourlyForecastSection(hourly = weather.hourly)
        
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun CityHeader(city: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = city,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun CurrentWeatherDisplay(weather: WeatherUiState.Success) {
    val textColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = weather.condition,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.9f)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "${weather.currentTemp.toInt()}°",
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 120.sp,
                fontWeight = FontWeight.Thin,
                color = textColor,
                textAlign = TextAlign.Center
            )
        )

        Text(
            text = "Feels like ${weather.feelsLike.toInt()}°",
            style = MaterialTheme.typography.titleMedium.copy(
                color = textColor.copy(alpha = 0.8f)
            )
        )

        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            TemperaturePill(label = "H", value = weather.high.toInt())
            TemperaturePill(label = "L", value = weather.low.toInt())
        }
    }
}

@Composable
fun TemperaturePill(label: String, value: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = "$label: $value°",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun WeatherDetailsGrid(weather: WeatherUiState.Success) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(28.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            DetailItem(icon = Icons.Rounded.WaterDrop, label = "Humidity", value = "${weather.humidity.toInt()}%")
            DetailItem(icon = Icons.Rounded.Air, label = "Wind", value = "${weather.windSpeed.toInt()} km/h")
            DetailItem(icon = Icons.Rounded.Compress, label = "Pressure", value = "${weather.pressure.toInt()} hPa")
        }
    }
}

@Composable
fun DetailItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon, 
            contentDescription = null, 
            tint = MaterialTheme.colorScheme.primary, 
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value, 
            color = MaterialTheme.colorScheme.onSurface, 
            fontWeight = FontWeight.Bold, 
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = label, 
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), 
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun HourlyForecastSection(hourly: List<HourlyForecast>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Next 24 Hours",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            ),
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(48.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
        ) {
            LazyRow(
                modifier = Modifier.padding(vertical = 24.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(hourly) { hour ->
                    Column(
                        modifier = Modifier
                            .width(72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = hour.time, 
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = getWeatherEmoji(hour.weatherCode), fontSize = 32.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "${hour.temp.toInt()}°", 
                            color = MaterialTheme.colorScheme.onSurface, 
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

fun getWeatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1, 2, 3 -> "⛅"
    45, 48 -> "🌫️"
    51, 53, 55 -> "🌦️"
    61, 63, 65 -> "🌧️"
    71, 73, 75 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "☁️"
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message, 
            color = MaterialTheme.colorScheme.onBackground, 
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onRetry, 
            modifier = Modifier.padding(top = 32.dp),
            shape = CircleShape
        ) {
            Text("Try Again")
        }
    }
}

@Composable
fun CityDialog(
    suggestions: List<GeocodeResult>,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Location", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                TextField(
                    value = text,
                    onValueChange = { 
                        text = it
                        onSearch(it)
                    },
                    placeholder = { Text("Search city...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        LazyColumn {
                            items(suggestions) { result ->
                                ListItem(
                                    headlineContent = { Text(result.name, fontWeight = FontWeight.Bold) },
                                    supportingContent = { Text("${result.admin1 ?: ""}, ${result.country ?: ""}") },
                                    modifier = Modifier.clickable { onConfirm(result.name) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WeatherApp()
        }
    }
}
