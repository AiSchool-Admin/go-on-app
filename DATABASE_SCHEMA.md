# DATABASE_SCHEMA.md - Firestore Database Structure

## 📊 Overview

GO-ON uses Firebase Firestore as its primary database. This document describes the complete data structure.

---

## 🗄 Collections

### 1. users
User profiles for all user types.

```javascript
users/{userId}
{
  // Basic Info
  id: string,                    // Firebase Auth UID
  email: string | null,
  phone: string,                 // Required - Egyptian format +201XXXXXXXXX
  name: string,
  avatarUrl: string | null,
  
  // User Type
  userType: 'passenger' | 'sender' | 'driver' | 'admin',
  isDriver: boolean,             // true if also registered as driver
  
  // Preferences
  language: 'ar' | 'en',
  defaultPaymentMethod: 'cash' | 'wallet' | 'card',
  
  // Wallet
  walletBalance: number,         // Current balance in EGP
  
  // Saved Places
  savedPlaces: [
    {
      id: string,
      name: string,              // 'البيت', 'الشغل'
      address: string,
      location: GeoPoint,
    }
  ],
  
  // Stats
  totalRides: number,
  totalShipments: number,
  totalSpent: number,
  
  // Gamification
  points: number,
  level: 'bronze' | 'silver' | 'gold' | 'platinum',
  
  // Metadata
  createdAt: Timestamp,
  updatedAt: Timestamp,
  lastActiveAt: Timestamp,
  fcmToken: string | null,       // For push notifications
  
  // Status
  isActive: boolean,
  isVerified: boolean,
}
```

**Subcollections:**
```javascript
users/{userId}/wallet_transactions/{transactionId}
{
  id: string,
  type: 'topup' | 'payment' | 'refund' | 'bonus',
  amount: number,
  balanceAfter: number,
  description: string,
  relatedId: string | null,      // rideId or shipmentId
  createdAt: Timestamp,
}
```

---

### 2. drivers
Driver-specific profiles (extends user data).

```javascript
drivers/{driverId}
{
  // Reference
  id: string,                    // Same as userId
  userId: string,
  
  // Personal Info
  name: string,
  phone: string,
  whatsappNumber: string | null,
  avatarUrl: string | null,
  
  // Verification Documents
  nationalId: string,
  nationalIdImage: string,       // Storage URL
  licenseNumber: string,
  licenseImage: string,
  licenseExpiry: Timestamp,
  
  // Vehicle Reference
  vehicleId: string,
  
  // Service Types
  services: {
    rides: boolean,              // Passenger rides
    freight: boolean,            // Shipping/delivery
    intercity: boolean,          // Long distance
  },
  
  // Working Hours
  workingHours: {
    start: string,               // '08:00'
    end: string,                 // '22:00'
  },
  workingDays: [string],         // ['sun', 'mon', 'tue', ...]
  
  // Status
  isOnline: boolean,
  isAvailable: boolean,
  currentLocation: GeoPoint | null,
  lastLocationUpdate: Timestamp | null,
  
  // Ratings
  rating: number,                // Average rating (1-5)
  totalRatings: number,
  
  // Stats
  totalRides: number,
  totalShipments: number,
  totalEarnings: number,
  completionRate: number,        // Percentage
  acceptanceRate: number,
  
  // Earnings
  pendingEarnings: number,       // Not yet withdrawn
  
  // Verification
  isVerified: boolean,
  verifiedAt: Timestamp | null,
  verifiedBy: string | null,
  
  // Metadata
  createdAt: Timestamp,
  updatedAt: Timestamp,
}
```

**Subcollections:**
```javascript
drivers/{driverId}/earnings/{periodId}
{
  id: string,                    // '2024-01' (monthly)
  rides: number,
  shipments: number,
  totalEarnings: number,
  commission: number,
  netEarnings: number,
}
```

---

### 3. vehicles
Vehicle information for drivers.

```javascript
vehicles/{vehicleId}
{
  id: string,
  driverId: string,
  
  // Vehicle Info
  type: 'car' | 'motorcycle' | 'van' | 'truck',
  category: 'economy' | 'comfort' | 'premium' | 'cargo',
  
  // Details
  make: string,                  // 'Toyota'
  model: string,                 // 'Corolla'
  year: number,                  // 2020
  color: string,
  plateNumber: string,           // Egyptian plate format
  
  // Capacity
  passengerCapacity: number,     // For rides
  cargoCapacity: number | null,  // For freight (kg)
  
  // Images
  images: [string],              // Storage URLs
  
  // Documents
  registrationNumber: string,
  registrationImage: string,
  registrationExpiry: Timestamp,
  insuranceImage: string | null,
  insuranceExpiry: Timestamp | null,
  
  // Status
  isActive: boolean,
  isVerified: boolean,
  
  // Metadata
  createdAt: Timestamp,
  updatedAt: Timestamp,
}
```

---

### 4. rides
Passenger ride requests and history.

```javascript
rides/{rideId}
{
  id: string,
  
  // Participants
  userId: string,                // Passenger
  driverId: string | null,       // Assigned driver
  
  // Locations
  origin: {
    address: string,
    location: GeoPoint,
    name: string | null,         // 'البيت'
  },
  destination: {
    address: string,
    location: GeoPoint,
    name: string | null,
  },
  
  // Route Info
  distanceKm: number,
  estimatedMinutes: number,
  actualMinutes: number | null,
  
  // Source (where was this ride booked from)
  source: 'go-on' | 'uber' | 'careem' | 'indriver' | 'independent',
  
  // Pricing
  estimatedPrice: number,
  finalPrice: number | null,
  currency: 'EGP',
  priceBreakdown: {
    baseFare: number,
    distanceFare: number,
    timeFare: number,
    discount: number,
    commission: number,
  } | null,
  
  // Payment
  paymentMethod: 'cash' | 'wallet' | 'card',
  paymentStatus: 'pending' | 'completed' | 'refunded',
  
  // Status
  status: 'searching' | 'accepted' | 'arrived' | 'started' | 'completed' | 'cancelled',
  
  // Timestamps
  requestedAt: Timestamp,
  acceptedAt: Timestamp | null,
  arrivedAt: Timestamp | null,
  startedAt: Timestamp | null,
  completedAt: Timestamp | null,
  cancelledAt: Timestamp | null,
  
  // Cancellation
  cancelledBy: 'user' | 'driver' | 'system' | null,
  cancellationReason: string | null,
  
  // Rating
  userRating: number | null,     // Driver rates user
  driverRating: number | null,   // User rates driver
  userReview: string | null,
  driverReview: string | null,
  
  // Metadata
  createdAt: Timestamp,
  updatedAt: Timestamp,
}
```

---

### 5. shipments
Freight/shipping orders.

```javascript
shipments/{shipmentId}
{
  id: string,
  
  // Participants
  senderId: string,              // User who sends
  receiverId: string | null,     // Can be guest (no account)
  driverId: string | null,       // Assigned driver
  courierId: string | null,      // If passenger-as-courier
  
  // Receiver Info (if no account)
  receiverName: string,
  receiverPhone: string,
  
  // Package Info
  package: {
    type: 'documents' | 'small_box' | 'large_box' | 'furniture' | 'fragile' | 'food',
    description: string,
    weightKg: number,
    dimensions: {
      length: number,
      width: number,
      height: number,
    } | null,
    value: number | null,        // Declared value for insurance
    images: [string],
  },
  
  // Locations
  pickup: {
    address: string,
    location: GeoPoint,
    contactName: string,
    contactPhone: string,
    notes: string | null,
  },
  delivery: {
    address: string,
    location: GeoPoint,
    contactName: string,
    contactPhone: string,
    notes: string | null,
  },
  
  // Route Info
  distanceKm: number,
  estimatedMinutes: number,
  
  // Service Options
  serviceType: 'express' | 'standard' | 'economy',
  isFragile: boolean,
  requiresSignature: boolean,
  isCOD: boolean,                // Cash on Delivery
  codAmount: number | null,
  
  // Pricing
  price: number,
  currency: 'EGP',
  priceBreakdown: {
    baseFare: number,
    distanceFare: number,
    weightFare: number,
    expressCharge: number,
    insurance: number,
    codFee: number,
    discount: number,
    commission: number,
  },
  
  // Payment
  paymentMethod: 'prepaid' | 'cod' | 'wallet',
  paymentStatus: 'pending' | 'collected' | 'transferred' | 'refunded',
  
  // Status
  status: 'pending' | 'accepted' | 'picked_up' | 'in_transit' | 'delivered' | 'cancelled' | 'returned',
  
  // Tracking
  trackingHistory: [
    {
      status: string,
      location: GeoPoint | null,
      timestamp: Timestamp,
      note: string | null,
    }
  ],
  
  // Proof of Delivery
  pickupPhoto: string | null,
  deliveryPhoto: string | null,
  signatureImage: string | null,
  
  // Timestamps
  requestedAt: Timestamp,
  acceptedAt: Timestamp | null,
  pickedUpAt: Timestamp | null,
  deliveredAt: Timestamp | null,
  cancelledAt: Timestamp | null,
  
  // Rating
  senderRating: number | null,
  driverRating: number | null,
  
  // Metadata
  createdAt: Timestamp,
  updatedAt: Timestamp,
}
```

---

### 6. price_snapshots
Cached prices from other apps (for analytics and ML).

```javascript
price_snapshots/{snapshotId}
{
  id: string,
  
  // Location
  origin: GeoPoint,
  destination: GeoPoint,
  originAddress: string,
  destinationAddress: string,
  distanceKm: number,
  
  // Prices
  prices: {
    uber: {
      available: boolean,
      uberX: number | null,
      uberComfort: number | null,
      uberXL: number | null,
      eta: number | null,
    },
    careem: {
      available: boolean,
      go: number | null,
      comfort: number | null,
      eta: number | null,
    },
    indriver: {
      available: boolean,
      suggestedPrice: number | null,
      eta: number | null,
    },
    swvl: {
      available: boolean,
      price: number | null,
      nextBus: Timestamp | null,
    },
    independent: {
      available: boolean,
      averagePrice: number | null,
      driversCount: number,
    },
  },
  
  // Context
  dayOfWeek: number,             // 0-6
  hourOfDay: number,             // 0-23
  isRushHour: boolean,
  weather: string | null,
  
  // Metadata
  capturedAt: Timestamp,
  capturedBy: string | null,     // userId if user-contributed
}
```

---

### 7. transactions
All financial transactions.

```javascript
transactions/{transactionId}
{
  id: string,
  
  // Parties
  fromUserId: string | null,     // null for top-up
  toUserId: string | null,       // null for withdrawal
  
  // Type
  type: 'topup' | 'ride_payment' | 'shipment_payment' | 'driver_payout' | 'refund' | 'bonus' | 'commission',
  
  // Amount
  amount: number,
  currency: 'EGP',
  
  // Related
  relatedType: 'ride' | 'shipment' | 'topup' | null,
  relatedId: string | null,
  
  // Payment Details
  paymentMethod: 'cash' | 'wallet' | 'card' | 'fawry' | 'vodafone_cash',
  paymentGateway: string | null,
  gatewayTransactionId: string | null,
  
  // Status
  status: 'pending' | 'completed' | 'failed' | 'refunded',
  
  // Metadata
  description: string,
  createdAt: Timestamp,
  completedAt: Timestamp | null,
}
```

---

### 8. ratings
Ratings and reviews.

```javascript
ratings/{ratingId}
{
  id: string,
  
  // Participants
  fromUserId: string,
  toUserId: string,
  
  // Related
  relatedType: 'ride' | 'shipment',
  relatedId: string,
  
  // Rating
  rating: number,                // 1-5
  review: string | null,
  
  // Tags
  tags: [string],                // ['friendly', 'clean_car', 'fast']
  
  // Metadata
  createdAt: Timestamp,
}
```

---

### 9. notifications
Push notification history.

```javascript
notifications/{notificationId}
{
  id: string,
  userId: string,
  
  // Content
  title: string,
  body: string,
  data: object | null,
  
  // Status
  isRead: boolean,
  readAt: Timestamp | null,
  
  // Metadata
  createdAt: Timestamp,
}
```

---

### 10. app_config
Application configuration (admin-managed).

```javascript
app_config/settings
{
  // Pricing
  pricing: {
    baseFare: number,
    perKmRate: number,
    perMinuteRate: number,
    minimumFare: number,
    commissionRate: number,      // Platform commission %
    rushHourMultiplier: number,
  },
  
  // Freight Pricing
  freightPricing: {
    baseFare: number,
    perKmRate: number,
    perKgRate: number,
    expressMultiplier: number,
    codFeePercent: number,
  },
  
  // Service Areas
  serviceAreas: [
    {
      name: string,
      center: GeoPoint,
      radiusKm: number,
      isActive: boolean,
    }
  ],
  
  // Features
  features: {
    ridesEnabled: boolean,
    freightEnabled: boolean,
    walletEnabled: boolean,
    codEnabled: boolean,
  },
  
  // Versions
  minAppVersion: string,
  latestAppVersion: string,
  
  // Metadata
  updatedAt: Timestamp,
}
```

---

## 🔍 Indexes

### Composite Indexes Needed

```javascript
// rides - for user history
Collection: rides
Fields: userId ASC, createdAt DESC

// rides - for driver history
Collection: rides
Fields: driverId ASC, createdAt DESC

// shipments - for sender history
Collection: shipments
Fields: senderId ASC, createdAt DESC

// drivers - for nearby search
Collection: drivers
Fields: isOnline ASC, services.rides ASC, currentLocation ASC

// price_snapshots - for analytics
Collection: price_snapshots
Fields: capturedAt DESC, hourOfDay ASC
```

---

## 📊 Data Relationships

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   users ──────────┬──────────── rides                          │
│     │             │               │                            │
│     │             │               │                            │
│     ▼             ▼               ▼                            │
│   drivers ──── vehicles        ratings                         │
│     │                             │                            │
│     │                             │                            │
│     ▼                             ▼                            │
│   shipments ─────────────── transactions                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔐 Security Rules Summary

```javascript
// Users: Own data only
// Drivers: Public read, own write
// Rides: Creator and assigned driver
// Shipments: Sender, receiver, and driver
// Transactions: Related parties only
// Ratings: Public read, creator write
// App_config: Admin only write, public read
```

---

## 💾 Data Migration Notes

1. Start with empty collections
2. Seed `app_config/settings` with defaults
3. Create admin user manually
4. Test data can be added via Firebase Console

---

## 📈 Analytics Events to Track

```javascript
// User Events
'user_registered'
'user_logged_in'
'user_became_driver'

// Ride Events
'ride_requested'
'ride_accepted'
'ride_completed'
'ride_cancelled'

// Shipment Events
'shipment_created'
'shipment_picked_up'
'shipment_delivered'

// Financial Events
'wallet_topup'
'payment_completed'
'driver_payout'

// Feature Events
'price_comparison_viewed'
'overlay_activated'
'driver_contacted_whatsapp'
```
