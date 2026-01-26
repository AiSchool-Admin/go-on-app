# Server-Side Pricing Setup Guide

## Overview

GO-ON supports two pricing modes:
1. **Client-Side (الجهاز)**: Uses Android Accessibility Service on user's phone
2. **Server-Side (السيرفر)**: Uses emulators on server to fetch prices

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      SERVER (Railway)                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │  Emulator 1 │  │  Emulator 2 │  │  Emulator 3 │  ...    │
│  │    Uber     │  │   Careem    │  │   InDriver  │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
│         └────────────────┼────────────────┘                 │
│                          ▼                                  │
│              ┌─────────────────────┐                        │
│              │   Price Aggregator  │                        │
│              │  (pricingService)   │                        │
│              └──────────┬──────────┘                        │
└─────────────────────────┼───────────────────────────────────┘
                          │
                          ▼
              ┌─────────────────────┐
              │     SUPABASE        │
              │   price_cache       │
              └──────────┬──────────┘
                          │
           ┌──────────────┼──────────────┐
           ▼              ▼              ▼
      [User App 1]   [User App 2]   [User App 3]
```

## Database Tables

### 1. price_cache
Stores fetched prices with TTL (5 minutes default).

```sql
-- Key fields:
origin_lat, origin_lng     -- Pickup coordinates
dest_lat, dest_lng         -- Destination coordinates
prices                     -- JSONB with all app prices
best_app, best_price       -- Computed best option
expires_at                 -- Cache expiry time
```

### 2. price_requests
Queue for server-side fetching.

### 3. pricing_config
Configuration for pricing modes.

### 4. app_pricing_stats
Formula coefficients for estimated prices.

## API Endpoints

### POST /api/pricing/prices
Get prices for a route.

```json
{
  "originLat": 30.0444,
  "originLng": 31.2357,
  "destLat": 30.0626,
  "destLng": 31.2497,
  "originAddress": "التحرير",
  "destAddress": "مصر الجديدة",
  "mode": "auto",
  "forceRefresh": false
}
```

Response:
```json
{
  "success": true,
  "source": "cache",
  "prices": {
    "uber": { "price": 65, "eta": 3, "surge": 1.0 },
    "careem": { "price": 58, "eta": 5, "surge": 1.2 },
    "indriver": { "price": 45, "eta": 8 },
    "bolt": { "price": 52, "eta": 4 },
    "didi": { "price": 48, "eta": 6 }
  },
  "bestApp": "indriver",
  "bestPrice": 45,
  "duration": 12
}
```

### POST /api/pricing/cache
Save prices to cache (from client or emulator).

### POST /api/pricing/estimated
Get estimated prices only (formula-based).

### GET /api/pricing/config
Get pricing configuration.

### GET /api/pricing/apps
Get list of supported apps.

## Pricing Modes

| Mode | Description | Speed | Accuracy |
|------|-------------|-------|----------|
| `auto` | Chooses best available | Variable | Best available |
| `hybrid` | Server → Client → Estimated | Medium | High |
| `server` | Server emulators only | Fast | High (if fresh) |
| `client` | User's phone only | Slow | Very High |
| `estimated` | Formula calculation | Instant | Approximate |

## Flutter Integration

```dart
// Get pricing service
final pricingService = ref.watch(pricingStrategyProvider);

// Set mode
await pricingService.setMode(PricingMode.hybrid);

// Get prices
final result = await pricingService.getPrices(
  origin: LatLng(30.0444, 31.2357),
  destination: LatLng(30.0626, 31.2497),
  mode: PricingMode.auto,
);

print('Best: ${result.bestApp} - ${result.bestPrice} EGP');
```

## Emulator Setup (Future)

### Requirements
- Docker with Android emulator support
- OR Genymotion Cloud subscription
- OR Physical devices with ADB

### Environment Variables
```env
EMULATOR_ENABLED=true
EMULATOR_HOST=localhost
EMULATOR_ADB_PORT=5555
EMULATOR_COUNT=5
```

### Implementation Options

1. **Docker + Android Emulator**
   - Use `budtmo/docker-android` image
   - Configure ADB network access
   - Run automation scripts

2. **Genymotion Cloud**
   - API access for cloud emulators
   - Pay-per-use pricing
   - No infrastructure management

3. **Device Farm**
   - Physical devices connected to server
   - Most reliable but requires hardware

## Cost Estimation

### Server Costs (Railway)
- Base: $5/month
- RAM: ~$10/GB/month
- 5 emulators × 4GB = 20GB ≈ $200/month

### Per-Request Cost
- With caching: ~$0.002-0.005 per request
- 30,000 requests/month ≈ $60-150

### Optimization Tips
1. Cache aggressively (5-10 min TTL)
2. Use estimated prices for initial display
3. Pre-cache popular routes
4. Scale emulators based on demand

## Migration

Run the migration to create tables:
```bash
cd supabase
supabase db push
```

Or manually:
```sql
-- Run: supabase/migrations/20240126000000_add_server_pricing.sql
```

## Troubleshooting

### Server not responding
1. Check Railway logs
2. Verify SUPABASE_URL and SUPABASE_SERVICE_KEY
3. Test `/health` endpoint

### Prices not caching
1. Check Supabase RLS policies
2. Verify service key has write access
3. Check `expires_at` values

### Emulator issues
1. Verify ADB connectivity
2. Check emulator logs
3. Ensure apps are logged in
