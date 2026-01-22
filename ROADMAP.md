# GO-ON Roadmap - خارطة الطريق

## الأولويات القادمة للـ Launch

### 1. [ ] Paymob Integration - تكامل الدفع
**الأولوية:** عالية جداً - مطلوب للـ launch

**المهام:**
- [ ] إعداد حساب Paymob وتفعيله
- [ ] تكامل Paymob SDK في التطبيق
- [ ] دعم بطاقات الائتمان (Visa/Mastercard)
- [ ] دعم المحافظ الإلكترونية (Vodafone Cash, Fawry, etc.)
- [ ] إضافة شاشة الدفع في التطبيق
- [ ] ربط المعاملات مع Supabase
- [ ] اختبار عمليات الدفع في sandbox
- [ ] اختبار الإنتاج قبل الإطلاق

**الملفات المتأثرة:**
- `mobile/lib/features/wallet/`
- `mobile/lib/core/services/payment_service.dart`
- `supabase/migrations/` (جدول transactions)

---

### 2. [ ] Real Booking - الحجز الفعلي
**الأولوية:** عالية - مطلوب للـ launch

**المهام:**
- [ ] دراسة Uber API و Careem API
- [ ] الحصول على API keys من الشركات
- [ ] إنشاء backend service للحجز (Railway)
- [ ] ربط الحجز مع التطبيق
- [ ] معالجة حالات الحجز (pending, confirmed, cancelled)
- [ ] إشعارات الحجز للمستخدم
- [ ] تتبع الرحلة في الوقت الفعلي

**الملفات المتأثرة:**
- `mobile/lib/features/rides/`
- `backend/src/services/booking/`
- `supabase/migrations/` (جدول rides)

---

### 3. [ ] Testing - الاختبارات
**الأولوية:** عالية - قبل Play Store

**المهام:**
- [ ] Unit tests للـ services الأساسية
- [ ] Widget tests للشاشات الرئيسية
- [ ] Integration tests للـ user flows
- [ ] اختبار الـ Accessibility Service
- [ ] اختبار على أجهزة مختلفة
- [ ] اختبار الأداء (Performance testing)
- [ ] اختبار الأمان (Security testing)
- [ ] Beta testing مع مستخدمين حقيقيين

**الملفات المتأثرة:**
- `mobile/test/`

---

## المرحلة الثانية (بعد Launch)

### 4. [ ] Independent Drivers Network - شبكة السائقين المستقلين
- [ ] تسجيل السائقين المستقلين
- [ ] التحقق من المستندات
- [ ] نظام التقييمات
- [ ] WhatsApp Bot للتواصل

### 5. [ ] Freight/Shipping - خدمات الشحن
- [ ] تكامل Aramex API
- [ ] تكامل Bosta API
- [ ] مقارنة أسعار الشحن
- [ ] تتبع الشحنات

### 6. [ ] Admin Dashboard - لوحة التحكم
- [ ] إنشاء Next.js dashboard
- [ ] إدارة المستخدمين
- [ ] إدارة السائقين
- [ ] تقارير وإحصائيات
- [ ] إدارة الشكاوى

---

## المرحلة الثالثة (التوسع)

### 7. [ ] Advanced Features
- [ ] برنامج الولاء والنقاط
- [ ] الاشتراكات الشهرية
- [ ] خصومات وعروض
- [ ] دعم مدن إضافية

### 8. [ ] Performance Optimization
- [ ] تحسين استهلاك البطارية
- [ ] تحسين سرعة التطبيق
- [ ] Caching strategies
- [ ] Offline mode كامل

---

## ملاحظات مهمة

- **Android فقط** - بسبب Accessibility Services
- **اللغة العربية أولاً** - RTL support
- **الأمان** - تشفير البيانات الحساسة
- **الخصوصية** - موافقة واضحة على الأذونات

---

*آخر تحديث: يناير 2026*
