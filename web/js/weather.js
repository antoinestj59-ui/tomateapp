// Météo réelle via Open-Meteo (aucune clé), mise en cache pour un usage hors ligne honnête.
import { cacheGet, cacheSet } from './db.js';

const CACHE_KEY = 'weather';

const CODES = {
  0: ['Ciel dégagé', '☀️'],
  1: ['Plutôt dégagé', '🌤️'],
  2: ['Partiellement nuageux', '⛅'],
  3: ['Couvert', '☁️'],
  45: ['Brouillard', '🌫️'],
  48: ['Brouillard givrant', '🌫️'],
  51: ['Bruine légère', '🌦️'],
  53: ['Bruine', '🌦️'],
  55: ['Bruine forte', '🌧️'],
  61: ['Pluie légère', '🌦️'],
  63: ['Pluie', '🌧️'],
  65: ['Pluie forte', '🌧️'],
  66: ['Pluie verglaçante', '🌧️'],
  67: ['Pluie verglaçante forte', '🌧️'],
  71: ['Neige légère', '🌨️'],
  73: ['Neige', '🌨️'],
  75: ['Neige forte', '❄️'],
  77: ['Grains de neige', '🌨️'],
  80: ['Averses légères', '🌦️'],
  81: ['Averses', '🌧️'],
  82: ['Averses violentes', '⛈️'],
  85: ['Averses de neige', '🌨️'],
  86: ['Averses de neige fortes', '❄️'],
  95: ['Orage', '⛈️'],
  96: ['Orage avec grêle', '⛈️'],
  99: ['Orage violent avec grêle', '⛈️'],
};

export function describeCode(code) {
  return CODES[code] ?? ['Conditions inconnues', '🌡️'];
}

export const cachedWeather = () => cacheGet(CACHE_KEY);

export async function fetchWeather(latitude, longitude, label) {
  const params = new URLSearchParams({
    latitude: String(latitude),
    longitude: String(longitude),
    current: 'temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,cloud_cover',
    hourly: 'temperature_2m,precipitation_probability,weather_code',
    daily: 'weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_probability_max',
    timezone: 'auto',
    forecast_days: '5',
  });
  const response = await fetch(`https://api.open-meteo.com/v1/forecast?${params}`);
  if (!response.ok) throw new Error(`Météo indisponible (HTTP ${response.status})`);
  const data = await response.json();
  const entry = {
    place: { latitude, longitude, label },
    fetchedAt: Date.now(),
    timezone: data.timezone,
    current: data.current,
    hourly: {
      time: data.hourly?.time?.slice(0, 24) ?? [],
      temperature: data.hourly?.temperature_2m?.slice(0, 24) ?? [],
      precipitation: data.hourly?.precipitation_probability?.slice(0, 24) ?? [],
      code: data.hourly?.weather_code?.slice(0, 24) ?? [],
    },
    daily: data.daily ?? null,
  };
  await cacheSet(CACHE_KEY, entry);
  return entry;
}

export async function searchCity(query) {
  const params = new URLSearchParams({ name: query, count: '5', language: 'fr', format: 'json' });
  const response = await fetch(`https://geocoding-api.open-meteo.com/v1/search?${params}`);
  if (!response.ok) throw new Error(`Recherche indisponible (HTTP ${response.status})`);
  const data = await response.json();
  return (data.results ?? []).map((r) => ({
    label: [r.name, r.admin1, r.country].filter(Boolean).join(', '),
    latitude: r.latitude,
    longitude: r.longitude,
  }));
}

export function weatherAge(fetchedAt) {
  if (!fetchedAt) return 'jamais actualisée';
  const minutes = Math.max(0, Math.round((Date.now() - fetchedAt) / 60000));
  if (minutes < 1) return "à l'instant";
  if (minutes < 60) return `il y a ${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `il y a ${hours} h`;
  return `il y a ${Math.round(hours / 24)} j`;
}
