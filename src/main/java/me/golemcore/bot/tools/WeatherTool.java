/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.http.FeignClientFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import feign.Param;
import feign.RequestLine;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for getting weather information using Open-Meteo API.
 *
 * <p>
 * Uses the free Open-Meteo API — no API key required. Provides current weather
 * conditions for any location worldwide.
 *
 * <p>
 * Process:
 * <ol>
 * <li>Geocode location name to coordinates (Open-Meteo Geocoding API)
 * <li>Fetch current weather for coordinates (Open-Meteo Weather API)
 * </ol>
 *
 * <p>
 * Always enabled.
 *
 * @see <a href="https://open-meteo.com/">Open-Meteo API</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeatherTool implements ToolComponent {

    // JSON Schema constants
    private static final String SCHEMA_TYPE = "type";
    private static final String SCHEMA_OBJECT = "object";
    private static final String SCHEMA_STRING = "string";
    private static final String SCHEMA_PROPERTIES = "properties";
    private static final String SCHEMA_REQUIRED = "required";

    // Parameter names
    private static final String PARAM_LOCATION = "location";

    // Weather codes
    private static final int CODE_CLEAR = 0;
    private static final int CODE_PARTLY_CLOUDY_MIN = 1;
    private static final int CODE_PARTLY_CLOUDY_MAX = 3;
    private static final int CODE_FOGGY_MIN = 45;
    private static final int CODE_FOGGY_MAX = 48;
    private static final int CODE_DRIZZLE_MIN = 51;
    private static final int CODE_DRIZZLE_MAX = 55;
    private static final int CODE_RAIN_MIN = 61;
    private static final int CODE_RAIN_MAX = 65;
    private static final int CODE_FREEZING_RAIN_MIN = 66;
    private static final int CODE_FREEZING_RAIN_MAX = 67;
    private static final int CODE_SNOW_MIN = 71;
    private static final int CODE_SNOW_MAX = 75;
    private static final int CODE_SNOW_GRAINS = 77;
    private static final int CODE_RAIN_SHOWERS_MIN = 80;
    private static final int CODE_RAIN_SHOWERS_MAX = 82;
    private static final int CODE_SNOW_SHOWERS_MIN = 85;
    private static final int CODE_SNOW_SHOWERS_MAX = 86;
    private static final int CODE_THUNDERSTORM = 95;
    private static final int CODE_THUNDERSTORM_HAIL_MIN = 96;
    private static final int CODE_THUNDERSTORM_HAIL_MAX = 99;

    private final FeignClientFactory feignClientFactory;

    private GeocodingApi geocodingApi;
    private WeatherApi weatherApi;

    @PostConstruct
    public void init() {
        this.geocodingApi = feignClientFactory.create(GeocodingApi.class, "https://geocoding-api.open-meteo.com");
        this.weatherApi = feignClientFactory.create(WeatherApi.class, "https://api.open-meteo.com");
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("weather")
                .description("Get current weather for a location. Uses Open-Meteo free API.")
                .inputSchema(Map.of(
                        SCHEMA_TYPE, SCHEMA_OBJECT,
                        SCHEMA_PROPERTIES, Map.of(
                                PARAM_LOCATION, Map.of(
                                        SCHEMA_TYPE, SCHEMA_STRING,
                                        "description", "City name or location (e.g., 'London', 'New York', 'Tokyo')")),
                        SCHEMA_REQUIRED, List.of(PARAM_LOCATION)))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String location = (String) parameters.get(PARAM_LOCATION);
            if (location == null || location.isBlank()) {
                return ToolResult.failure("Location is required");
            }

            try {
                // First, geocode the location
                GeocodingResponse geocoding = geocodingApi.search(location, 1);
                if (geocoding.getResults() == null || geocoding.getResults().isEmpty()) {
                    return ToolResult.failure("Location not found: " + location);
                }

                GeoResult geoResult = geocoding.getResults().get(0);

                // Then get weather
                WeatherResponse weather = weatherApi.getCurrentWeather(
                        geoResult.getLatitude(),
                        geoResult.getLongitude(),
                        "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code");

                if (weather.getCurrentWeather() == null) {
                    return ToolResult.failure("Weather data not available");
                }

                CurrentWeather current = weather.getCurrentWeather();
                String description = getWeatherDescription(current.getWeatherCode());

                String output = String.format(
                        "Weather in %s, %s:%n" +
                                "- Conditions: %s%n" +
                                "- Temperature: %.1f°C%n" +
                                "- Humidity: %.0f%%%n" +
                                "- Wind Speed: %.1f km/h",
                        geoResult.getName(),
                        geoResult.getCountry(),
                        description,
                        current.getTemperature(),
                        current.getHumidity(),
                        current.getWindSpeed());

                return ToolResult.success(output, Map.of(
                        "location", geoResult.getName(),
                        "country", geoResult.getCountry(),
                        "temperature_celsius", current.getTemperature(),
                        "humidity_percent", current.getHumidity(),
                        "wind_speed_kmh", current.getWindSpeed(),
                        "weather_code", current.getWeatherCode(),
                        "description", description));

            } catch (Exception e) {
                log.error("Weather tool failed for location: {}", location, e);
                return ToolResult.failure("Failed to get weather: " + e.getMessage());
            }
        });
    }

    private String getWeatherDescription(int code) {
        if (code == CODE_CLEAR) {
            return "Clear sky";
        } else if (code >= CODE_PARTLY_CLOUDY_MIN && code <= CODE_PARTLY_CLOUDY_MAX) {
            return "Partly cloudy";
        } else if (code >= CODE_FOGGY_MIN && code <= CODE_FOGGY_MAX) {
            return "Foggy";
        } else if (code >= CODE_DRIZZLE_MIN && code <= CODE_DRIZZLE_MAX) {
            return "Drizzle";
        } else if (code >= CODE_RAIN_MIN && code <= CODE_RAIN_MAX) {
            return "Rain";
        } else if (code >= CODE_FREEZING_RAIN_MIN && code <= CODE_FREEZING_RAIN_MAX) {
            return "Freezing rain";
        } else if (code >= CODE_SNOW_MIN && code <= CODE_SNOW_MAX) {
            return "Snow";
        } else if (code == CODE_SNOW_GRAINS) {
            return "Snow grains";
        } else if (code >= CODE_RAIN_SHOWERS_MIN && code <= CODE_RAIN_SHOWERS_MAX) {
            return "Rain showers";
        } else if (code >= CODE_SNOW_SHOWERS_MIN && code <= CODE_SNOW_SHOWERS_MAX) {
            return "Snow showers";
        } else if (code == CODE_THUNDERSTORM) {
            return "Thunderstorm";
        } else if (code >= CODE_THUNDERSTORM_HAIL_MIN && code <= CODE_THUNDERSTORM_HAIL_MAX) {
            return "Thunderstorm with hail";
        } else {
            return "Unknown";
        }
    }

    // Feign API interfaces
    interface GeocodingApi {
        @RequestLine("GET /v1/search?name={name}&count={count}")
        GeocodingResponse search(@Param("name") String name, @Param("count") int count);
    }

    interface WeatherApi {
        @RequestLine("GET /v1/forecast?latitude={lat}&longitude={lon}&current_weather=true&current={current}")
        WeatherResponse getCurrentWeather(
                @Param("lat") double latitude,
                @Param("lon") double longitude,
                @Param("current") String current);
    }

    // Response DTOs
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GeocodingResponse {
        private List<GeoResult> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GeoResult {
        private String name;
        private String country;
        private double latitude;
        private double longitude;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WeatherResponse {
        @JsonProperty("current_weather")
        private CurrentWeather currentWeather;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CurrentWeather {
        private double temperature;
        @JsonProperty("relative_humidity_2m")
        private double humidity;
        @JsonProperty("wind_speed_10m")
        private double windSpeed;
        @JsonProperty("weathercode")
        private int weatherCode;
    }
}
