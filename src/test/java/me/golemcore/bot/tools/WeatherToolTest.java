package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.http.FeignClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeatherToolTest {

    private static final String PARAM_LOCATION = "location";
    private static final String LOCATION_LONDON = "London";
    private static final String LOCATION_TOKYO = "Tokyo";

    private FeignClientFactory feignClientFactory;
    private WeatherTool tool;

    @BeforeEach
    void setUp() {
        feignClientFactory = mock(FeignClientFactory.class);
        tool = new WeatherTool(feignClientFactory);
    }

    // ===== getDefinition =====

    @Test
    void shouldReturnValidDefinition() {
        ToolDefinition def = tool.getDefinition();
        assertEquals("weather", def.getName());
        assertNotNull(def.getDescription());
        assertNotNull(def.getInputSchema());
        assertTrue(def.getDescription().contains("weather"));
    }

    @Test
    void shouldRequireLocationParameter() {
        ToolDefinition def = tool.getDefinition();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = def.getInputSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains(PARAM_LOCATION));
    }

    // ===== isEnabled =====

    @Test
    void shouldAlwaysBeEnabled() {
        assertTrue(tool.isEnabled());
    }

    // ===== Input validation =====

    @Test
    void shouldFailWhenLocationIsNull() {
        ToolResult result = tool.execute(Map.of()).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Location is required"));
    }

    @Test
    void shouldFailWhenLocationIsBlank() {
        ToolResult result = tool.execute(Map.of(PARAM_LOCATION, "  ")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Location is required"));
    }

    // ===== Successful weather fetch =====

    @Test
    void shouldReturnWeatherDataForValidLocation() {
        WeatherTool.GeocodingApi geocodingApi = mock(WeatherTool.GeocodingApi.class);
        WeatherTool.WeatherApi weatherApi = mock(WeatherTool.WeatherApi.class);
        injectApis(geocodingApi, weatherApi);

        WeatherTool.GeoResult geoResult = new WeatherTool.GeoResult();
        geoResult.setName(LOCATION_LONDON);
        geoResult.setCountry("United Kingdom");
        geoResult.setLatitude(51.5074);
        geoResult.setLongitude(-0.1278);

        WeatherTool.GeocodingResponse geoResponse = new WeatherTool.GeocodingResponse();
        geoResponse.setResults(List.of(geoResult));
        when(geocodingApi.search(LOCATION_LONDON, 1)).thenReturn(geoResponse);

        WeatherTool.CurrentWeather currentWeather = new WeatherTool.CurrentWeather();
        currentWeather.setTemperature(15.3);
        currentWeather.setHumidity(72.0);
        currentWeather.setWindSpeed(12.5);
        currentWeather.setWeatherCode(0);

        WeatherTool.WeatherResponse weatherResponse = new WeatherTool.WeatherResponse();
        weatherResponse.setCurrentWeather(currentWeather);
        when(weatherApi.getCurrentWeather(eq(51.5074), eq(-0.1278), anyString())).thenReturn(weatherResponse);

        ToolResult result = tool.execute(Map.of(PARAM_LOCATION, LOCATION_LONDON)).join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(LOCATION_LONDON));
        assertTrue(result.getOutput().contains("United Kingdom"));
        assertTrue(result.getOutput().contains("15.3"));
        assertTrue(result.getOutput().contains("Clear sky"));
        assertNotNull(result.getData());
    }

    // ===== Location not found =====

    @Test
    void shouldFailWhenLocationNotFound() {
        WeatherTool.GeocodingApi geocodingApi = mock(WeatherTool.GeocodingApi.class);
        WeatherTool.WeatherApi weatherApi = mock(WeatherTool.WeatherApi.class);
        injectApis(geocodingApi, weatherApi);

        WeatherTool.GeocodingResponse emptyResponse = new WeatherTool.GeocodingResponse();
        emptyResponse.setResults(null);
        when(geocodingApi.search("Nonexistent", 1)).thenReturn(emptyResponse);

        ToolResult result = tool.execute(Map.of(PARAM_LOCATION, "Nonexistent")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Location not found"));
    }

    @Test
    void shouldFailWhenGeocodingReturnsEmptyList() {
        WeatherTool.GeocodingApi geocodingApi = mock(WeatherTool.GeocodingApi.class);
        WeatherTool.WeatherApi weatherApi = mock(WeatherTool.WeatherApi.class);
        injectApis(geocodingApi, weatherApi);

        WeatherTool.GeocodingResponse emptyResponse = new WeatherTool.GeocodingResponse();
        emptyResponse.setResults(List.of());
        when(geocodingApi.search("Empty", 1)).thenReturn(emptyResponse);

        ToolResult result = tool.execute(Map.of(PARAM_LOCATION, "Empty")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Location not found"));
    }

    // ===== Weather data not available =====

    @Test
    void shouldFailWhenWeatherDataNotAvailable() {
        WeatherTool.GeocodingApi geocodingApi = mock(WeatherTool.GeocodingApi.class);
        WeatherTool.WeatherApi weatherApi = mock(WeatherTool.WeatherApi.class);
        injectApis(geocodingApi, weatherApi);

        WeatherTool.GeoResult geoResult = new WeatherTool.GeoResult();
        geoResult.setName("Test");
        geoResult.setCountry("Country");
        geoResult.setLatitude(0.0);
        geoResult.setLongitude(0.0);

        WeatherTool.GeocodingResponse geoResponse = new WeatherTool.GeocodingResponse();
        geoResponse.setResults(List.of(geoResult));
        when(geocodingApi.search("Test", 1)).thenReturn(geoResponse);

        WeatherTool.WeatherResponse weatherResponse = new WeatherTool.WeatherResponse();
        weatherResponse.setCurrentWeather(null);
        when(weatherApi.getCurrentWeather(eq(0.0), eq(0.0), anyString())).thenReturn(weatherResponse);

        ToolResult result = tool.execute(Map.of(PARAM_LOCATION, "Test")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Weather data not available"));
    }

    // ===== API error =====

    @Test
    void shouldHandleApiException() {
        WeatherTool.GeocodingApi geocodingApi = mock(WeatherTool.GeocodingApi.class);
        WeatherTool.WeatherApi weatherApi = mock(WeatherTool.WeatherApi.class);
        injectApis(geocodingApi, weatherApi);

        when(geocodingApi.search("Error", 1)).thenThrow(new RuntimeException("Connection refused"));

        ToolResult result = tool.execute(Map.of(PARAM_LOCATION, "Error")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Failed to get weather"));
    }

    // ===== Weather code descriptions =====

    @ParameterizedTest
    @CsvSource({
            "0, Clear sky",
            "1, Partly cloudy",
            "2, Partly cloudy",
            "3, Partly cloudy",
            "45, Foggy",
            "48, Foggy",
            "51, Drizzle",
            "55, Drizzle",
            "61, Rain",
            "65, Rain",
            "66, Freezing rain",
            "67, Freezing rain",
            "71, Snow",
            "75, Snow",
            "77, Snow grains",
            "80, Rain showers",
            "82, Rain showers",
            "85, Snow showers",
            "86, Snow showers",
            "95, Thunderstorm",
            "96, Thunderstorm with hail",
            "99, Thunderstorm with hail",
            "100, Unknown"
    })
    void shouldReturnCorrectWeatherDescription(int code, String expected) {
        String description = ReflectionTestUtils.invokeMethod(tool, "getWeatherDescription", code);
        assertEquals(expected, description);
    }

    // ===== Weather data in result metadata =====

    @Test
    void shouldIncludeMetadataInSuccessfulResult() {
        WeatherTool.GeocodingApi geocodingApi = mock(WeatherTool.GeocodingApi.class);
        WeatherTool.WeatherApi weatherApi = mock(WeatherTool.WeatherApi.class);
        injectApis(geocodingApi, weatherApi);

        WeatherTool.GeoResult geoResult = new WeatherTool.GeoResult();
        geoResult.setName(LOCATION_TOKYO);
        geoResult.setCountry("Japan");
        geoResult.setLatitude(35.6762);
        geoResult.setLongitude(139.6503);

        WeatherTool.GeocodingResponse geoResponse = new WeatherTool.GeocodingResponse();
        geoResponse.setResults(List.of(geoResult));
        when(geocodingApi.search(LOCATION_TOKYO, 1)).thenReturn(geoResponse);

        WeatherTool.CurrentWeather currentWeather = new WeatherTool.CurrentWeather();
        currentWeather.setTemperature(25.0);
        currentWeather.setHumidity(65.0);
        currentWeather.setWindSpeed(8.0);
        currentWeather.setWeatherCode(61);

        WeatherTool.WeatherResponse weatherResponse = new WeatherTool.WeatherResponse();
        weatherResponse.setCurrentWeather(currentWeather);
        when(weatherApi.getCurrentWeather(eq(35.6762), eq(139.6503), anyString())).thenReturn(weatherResponse);

        ToolResult result = tool.execute(Map.of(PARAM_LOCATION, LOCATION_TOKYO)).join();

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(LOCATION_TOKYO, data.get(PARAM_LOCATION));
        assertEquals("Japan", data.get("country"));
        assertEquals(25.0, data.get("temperature_celsius"));
        assertEquals(65.0, data.get("humidity_percent"));
        assertEquals(8.0, data.get("wind_speed_kmh"));
        assertEquals(61, data.get("weather_code"));
        assertEquals("Rain", data.get("description"));
    }

    // ===== Helper =====

    private void injectApis(WeatherTool.GeocodingApi geocodingApi, WeatherTool.WeatherApi weatherApi) {
        ReflectionTestUtils.setField(tool, "geocodingApi", geocodingApi);
        ReflectionTestUtils.setField(tool, "weatherApi", weatherApi);
    }
}
