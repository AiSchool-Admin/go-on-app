# GO-ON - Smart Transport Aggregator

<div align="center">

**مصر تتحرك** | Egypt Moves

تطبيق تجميع خدمات النقل الذكي - ركاب وبضائع في تطبيق واحد

[![Flutter](https://img.shields.io/badge/Flutter-3.x-02569B?logo=flutter)](https://flutter.dev)
[![Supabase](https://img.shields.io/badge/Supabase-Backend-3ECF8E?logo=supabase)](https://supabase.com)
[![Railway](https://img.shields.io/badge/Railway-APIs-0B0D0E?logo=railway)](https://railway.app)
[![Android](https://img.shields.io/badge/Android-Only-3DDC84?logo=android)](https://developer.android.com)

</div>

---

## ما هو GO-ON؟

GO-ON هو تطبيق ذكي يجمع كل خدمات النقل في مصر في مكان واحد:

- **مقارنة أسعار الركوب** - أوبر، كريم، إندرايف، ديدي، والسائقين المستقلين
- **مقارنة أسعار الشحن** - أرامكس، بوسطة، DHL، والسائقين المستقلين
- **توفير 20-40%** على كل رحلة أو شحنة

---

## الميزات الرئيسية

### للركاب
- مقارنة فورية للأسعار من جميع التطبيقات
- حجز بضغطة واحدة
- شبكة سائقين مستقلين بأسعار أفضل
- تتبع الرحلة مباشرة
- الطبقة الشفافة (Floating Overlay) لعرض أفضل سعر

### للشحن
- مقارنة شركات الشحن والسائقين
- تتبع حي للشحنات (Realtime)
- الدفع عند الاستلام (COD)
- صور إثبات الاستلام

### للسائقين
- طلبات من مصادر متعددة في شاشة واحدة
- أرباح أعلى بعمولة أقل
- حرية العمل

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| **Mobile App** | Flutter 3.x (Dart) - Android Only |
| **State Management** | Riverpod |
| **Database** | Supabase (PostgreSQL) |
| **Auth** | Supabase Auth (Phone OTP) |
| **Realtime** | Supabase Realtime |
| **Storage** | Supabase Storage |
| **Additional APIs** | Railway (Node.js) |
| **Admin Dashboard** | Next.js 14 on Vercel |
| **Maps** | Google Maps Platform |
| **Payments** | Paymob API |

---

## Project Structure

```
go-on-app/
├── mobile/                         # Flutter Mobile App
│   ├── lib/
│   │   ├── main.dart               # App entry point
│   │   ├── core/                   # Core utilities
│   │   │   ├── constants/          # App constants & colors
│   │   │   ├── theme/              # App theme
│   │   │   ├── routes/             # App router (go_router)
│   │   │   └── services/           # Core services (Supabase)
│   │   ├── features/               # Feature modules
│   │   │   ├── auth/               # Authentication (login, OTP)
│   │   │   ├── home/               # Home screen
│   │   │   ├── rides/              # Passenger rides & price comparison
│   │   │   ├── freight/            # Shipping/freight
│   │   │   ├── tracking/           # Live tracking
│   │   │   ├── wallet/             # Digital wallet
│   │   │   └── profile/            # User profile
│   │   ├── models/                 # Data models
│   │   ├── providers/              # Riverpod providers
│   │   └── widgets/                # Reusable widgets
│   ├── assets/                     # Images, fonts
│   ├── android/                    # Android native code
│   └── pubspec.yaml                # Flutter dependencies
│
├── backend/                        # Railway Backend (Node.js)
│   ├── src/
│   │   ├── index.js                # Express server entry
│   │   └── services/
│   │       ├── ocr/                # OCR for price reading
│   │       ├── whatsapp/           # WhatsApp Bot
│   │       └── notifications/      # Push notifications
│   └── package.json
│
├── admin/                          # Vercel Admin Dashboard (Next.js)
│   ├── src/app/                    # Next.js App Router
│   └── package.json
│
├── supabase/                       # Supabase configuration
│   ├── migrations/                 # Database migrations
│   └── functions/                  # Edge Functions
│
├── docs/                           # Documentation
│   ├── GO-ON_PRD.md                # Product Requirements
│   ├── DATABASE_SCHEMA.md          # PostgreSQL Schema
│   └── GETTING_STARTED.md          # Setup Guide
│
└── CLAUDE.md                       # Claude Code Instructions
```

---

## Getting Started

### Prerequisites
- Flutter 3.x
- Android Studio / VS Code
- Supabase account
- Google Maps API key

### Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/your-username/go-on-app.git
   cd go-on-app
   ```

2. **Setup Flutter app**
   ```bash
   cd mobile
   flutter pub get
   ```

3. **Configure environment**
   - Update `lib/core/constants/app_constants.dart` with your Supabase and Google Maps keys

4. **Run the app**
   ```bash
   flutter run
   ```

### Database Setup

1. Create a new Supabase project
2. Run the migration in `supabase/migrations/20240101000000_initial_schema.sql`
3. Enable Realtime for `rides`, `shipments`, `drivers` tables

---

## Documentation

| Document | Description |
|----------|-------------|
| [CLAUDE.md](./CLAUDE.md) | Instructions for Claude Code |
| [GO-ON_PRD.md](./docs/GO-ON_PRD.md) | Product Requirements Document |
| [DATABASE_SCHEMA.md](./docs/DATABASE_SCHEMA.md) | PostgreSQL/Supabase Schema |
| [GETTING_STARTED.md](./docs/GETTING_STARTED.md) | Setup & Development Guide |

---

## Key Features Implementation Status

### MVP (Phase 1)
- [x] Project structure setup
- [x] Core theme and constants
- [x] Authentication screens (Login, OTP)
- [x] Home screen with navigation
- [x] Price comparison screen
- [x] Freight/shipping screen
- [x] Tracking screen
- [x] Wallet screen
- [x] Profile screen
- [x] Database schema (Supabase migration)
- [x] Backend API placeholder (Railway)
- [x] Admin dashboard placeholder (Vercel)
- [ ] Supabase integration (connection)
- [ ] Google Maps integration
- [ ] OCR price extraction
- [ ] WhatsApp driver network
- [ ] Payment integration (Paymob)
- [ ] Floating overlay (Android)

### Phase 2
- [ ] Price prediction (ML)
- [ ] Hybrid trips (multi-modal)
- [ ] Gamification system
- [ ] Passenger-as-courier

---

## Target Market

- **Egypt** (Primary)
- **MENA Region** (Future)

---

## License

This project is proprietary. All rights reserved.

---

<div align="center">

**Built for Egypt**

Powered by **Supabase** | **Flutter** | **Railway** | **Vercel**

</div>
