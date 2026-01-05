# GETTING_STARTED.md - دليل البدء مع Claude Code

## 🎯 مقدمة

هذا الدليل سيساعدك على البدء في تطوير GO-ON باستخدام Claude Code.
**لا تحتاج خبرة برمجية سابقة** - فقط اتبع الخطوات.

---

## 📋 المتطلبات الأساسية

### الحسابات المطلوبة
- [x] حساب GitHub ✅
- [x] حساب Claude Pro ✅
- [x] حساب Supabase (أنت تستخدمه في Xchange) ✅
- [x] حساب Railway (أنت تستخدمه في Xchange) ✅
- [x] حساب Vercel (أنت تستخدمه في Xchange) ✅
- [ ] حساب Google Cloud (للـ Maps API)

---

## 🚀 الخطوات

### الخطوة 1: ربط Claude Code مع GitHub

1. اذهب إلى **claude.ai/code**
2. اضغط **Connect GitHub**
3. سجل دخول GitHub واعطِ الصلاحيات
4. اختر Repository: **go-on-app**
5. اضغط **Start Session**

---

### الخطوة 2: إنشاء مشروع Supabase

1. افتح [supabase.com](https://supabase.com)
2. اضغط **New Project**
3. أدخل البيانات:
   ```
   Project name: go-on
   Database Password: (احفظه في مكان آمن!)
   Region: Frankfurt (eu-central-1) - الأقرب لمصر
   ```
4. انتظر حتى يكتمل الإنشاء (دقيقة تقريباً)

---

### الخطوة 3: إعداد Supabase

#### 3.1 تفعيل Phone Auth
1. في Supabase Dashboard → **Authentication** → **Providers**
2. فعّل **Phone**
3. (لاحقاً ستحتاج Twilio للـ SMS)

#### 3.2 الحصول على API Keys
1. اذهب إلى **Settings** → **API**
2. احفظ هذه القيم:
   ```
   Project URL: https://xxxxx.supabase.co
   anon (public): eyJhbGciOiJIUzI1NiIsInR5cCI6...
   service_role: eyJhbGciOiJIUzI1NiIsInR5cCI6... (سري!)
   ```

#### 3.3 تفعيل Realtime
1. اذهب إلى **Database** → **Replication**
2. فعّل Realtime للجداول:
   - rides
   - shipments
   - drivers

---

### الخطوة 4: إعداد Google Maps

#### 4.1 تفعيل APIs
1. افتح [console.cloud.google.com](https://console.cloud.google.com)
2. أنشئ مشروع جديد أو استخدم موجود
3. اذهب إلى **APIs & Services** → **Enable APIs**
4. فعّل:
   - Maps SDK for Android
   - Places API
   - Directions API
   - Geocoding API

#### 4.2 إنشاء API Key
1. اذهب إلى **APIs & Services** → **Credentials**
2. اضغط **Create Credentials** → **API Key**
3. احفظ الـ API Key

---

## 💬 التوجيه الأول لـ Claude Code

بعد ربط GitHub، انسخ والصق هذا التوجيه:

```
مرحباً Claude Code! 👋

أنا أعمل على مشروع GO-ON - تطبيق تجميع خدمات النقل في مصر.

📁 الملفات المهمة في المشروع:
- GO-ON_PRD.md (متطلبات المنتج)
- CLAUDE.md (تعليمات التطوير)
- DATABASE_SCHEMA.md (هيكل قاعدة البيانات)
- GETTING_STARTED.md (دليل البدء)

🛠 Tech Stack:
- Mobile: Flutter (Android فقط)
- Backend: Supabase (PostgreSQL + Auth + Realtime + Storage)
- Additional APIs: Railway (للـ OCR و WhatsApp Bot)
- Admin: Next.js على Vercel (لاحقاً)

📋 المهمة الأولى:
1. اقرأ جميع ملفات التوثيق في المشروع
2. أخبرني أنك فهمت الرؤية والميزات المطلوبة
3. أنشئ مشروع Flutter جديد بالهيكل المذكور في CLAUDE.md
4. أعد ملف README.md ليعكس المشروع الجديد

🎯 ملاحظات مهمة:
- أنا لست مطوراً - اشرح لي كل خطوة ببساطة
- أستخدم Supabase و Railway و Vercel في مشروع آخر
- التطبيق لـ Android فقط (بسبب Accessibility Services)
- اللغة الأساسية: العربية (RTL)

ابدأ بقراءة الملفات وأخبرني بفهمك للمشروع.
```

---

## 📝 أوامر مفيدة لـ Claude Code

### لإنشاء هيكل Flutter:
```
أنشئ مشروع Flutter جديد باسم go_on مع:
- دعم Android فقط
- الهيكل المذكور في CLAUDE.md
- إعداد Supabase
- إعداد Riverpod
```

### لإنشاء جدول في Supabase:
```
أنشئ migration لجدول profiles حسب DATABASE_SCHEMA.md
```

### لإنشاء شاشة:
```
أنشئ شاشة مقارنة الأسعار (PriceComparisonScreen) حسب التصميم في PRD
```

### لإصلاح خطأ:
```
عندي هذا الخطأ:
[الصق الخطأ هنا]
```

### للفهم:
```
اشرح لي بالعربي كيف يعمل [الشيء المحدد]
```

---

## 🔧 Environment Variables

### Flutter App (.env)
```env
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6...
GOOGLE_MAPS_API_KEY=AIzaSy...
```

### Railway Backend (.env)
```env
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_SERVICE_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6...
WHATSAPP_API_TOKEN=...
```

---

## 📱 تجربة التطبيق

### على جهاز Android حقيقي:
1. فعّل **Developer Options** على هاتفك
2. فعّل **USB Debugging**
3. وصّل الهاتف بالكمبيوتر
4. اطلب من Claude Code: `شغّل التطبيق على الجهاز المتصل`

### على Emulator:
```
ساعدني في إعداد Android Emulator لاختبار التطبيق
```

---

## 📋 Checklist للبدء

```
[ ] 1. ربط Claude Code مع go-on-app repository
[ ] 2. إنشاء مشروع Supabase جديد
[ ] 3. حفظ Supabase API Keys
[ ] 4. إنشاء Google Maps API Key
[ ] 5. إرسال التوجيه الأول لـ Claude Code
[ ] 6. متابعة تعليمات Claude Code
```

---

## 🆘 إذا واجهت مشكلة

### Claude Code لا يستجيب:
- أعد تحميل الصفحة
- أغلق الجلسة وافتح جديدة

### خطأ في الكود:
- انسخ رسالة الخطأ كاملة
- أرسلها لـ Claude Code

### لا تفهم ما يحدث:
```
اشرح لي بالعربي ما فعلته الآن ولماذا
```

### تريد التراجع:
```
تراجع عن آخر تغيير
```

---

## 🔗 روابط مفيدة

| الخدمة | الرابط |
|--------|--------|
| Claude Code | claude.ai/code |
| Supabase Dashboard | app.supabase.com |
| Railway | railway.app |
| Vercel | vercel.com |
| Google Cloud Console | console.cloud.google.com |

---

## 🎉 أنت جاهز!

بمجرد إتمام الخطوات أعلاه، أنت جاهز للبدء في بناء GO-ON!

**تذكر:** Claude Code هو مساعدك ومعلمك - اسأله أي سؤال!

بالتوفيق! 🚀
