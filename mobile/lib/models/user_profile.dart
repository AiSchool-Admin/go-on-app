/// User profile model - matches Supabase profiles table
class UserProfile {
  final String id;
  final String? email;
  final String phone;
  final String name;
  final String? avatarUrl;
  final UserType userType;
  final bool isDriver;
  final String language;
  final PaymentMethod defaultPaymentMethod;
  final double walletBalance;
  final int totalRides;
  final int totalShipments;
  final double totalSpent;
  final int points;
  final UserLevel level;
  final bool isActive;
  final bool isVerified;
  final String? fcmToken;
  final RideSortPreference rideSortPreference;
  final DateTime createdAt;
  final DateTime updatedAt;
  final DateTime lastActiveAt;

  UserProfile({
    required this.id,
    this.email,
    required this.phone,
    required this.name,
    this.avatarUrl,
    this.userType = UserType.passenger,
    this.isDriver = false,
    this.language = 'ar',
    this.defaultPaymentMethod = PaymentMethod.cash,
    this.walletBalance = 0.0,
    this.totalRides = 0,
    this.totalShipments = 0,
    this.totalSpent = 0.0,
    this.points = 0,
    this.level = UserLevel.bronze,
    this.isActive = true,
    this.isVerified = false,
    this.fcmToken,
    this.rideSortPreference = RideSortPreference.lowestPrice,
    required this.createdAt,
    required this.updatedAt,
    required this.lastActiveAt,
  });

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    return UserProfile(
      id: json['id'] as String,
      email: json['email'] as String?,
      phone: json['phone'] as String,
      name: json['name'] as String,
      avatarUrl: json['avatar_url'] as String?,
      userType: UserType.fromString(json['user_type'] as String? ?? 'passenger'),
      isDriver: json['is_driver'] as bool? ?? false,
      language: json['language'] as String? ?? 'ar',
      defaultPaymentMethod: PaymentMethod.fromString(
          json['default_payment_method'] as String? ?? 'cash'),
      walletBalance: (json['wallet_balance'] as num?)?.toDouble() ?? 0.0,
      totalRides: json['total_rides'] as int? ?? 0,
      totalShipments: json['total_shipments'] as int? ?? 0,
      totalSpent: (json['total_spent'] as num?)?.toDouble() ?? 0.0,
      points: json['points'] as int? ?? 0,
      level: UserLevel.fromString(json['level'] as String? ?? 'bronze'),
      isActive: json['is_active'] as bool? ?? true,
      isVerified: json['is_verified'] as bool? ?? false,
      fcmToken: json['fcm_token'] as String?,
      rideSortPreference: RideSortPreference.fromString(
          json['ride_sort_preference'] as String? ?? 'lowest_price'),
      createdAt: DateTime.parse(json['created_at'] as String),
      updatedAt: DateTime.parse(json['updated_at'] as String),
      lastActiveAt: DateTime.parse(json['last_active_at'] as String),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'email': email,
      'phone': phone,
      'name': name,
      'avatar_url': avatarUrl,
      'user_type': userType.value,
      'is_driver': isDriver,
      'language': language,
      'default_payment_method': defaultPaymentMethod.value,
      'wallet_balance': walletBalance,
      'total_rides': totalRides,
      'total_shipments': totalShipments,
      'total_spent': totalSpent,
      'points': points,
      'level': level.value,
      'is_active': isActive,
      'is_verified': isVerified,
      'fcm_token': fcmToken,
      'ride_sort_preference': rideSortPreference.value,
    };
  }

  UserProfile copyWith({
    String? id,
    String? email,
    String? phone,
    String? name,
    String? avatarUrl,
    UserType? userType,
    bool? isDriver,
    String? language,
    PaymentMethod? defaultPaymentMethod,
    double? walletBalance,
    int? totalRides,
    int? totalShipments,
    double? totalSpent,
    int? points,
    UserLevel? level,
    bool? isActive,
    bool? isVerified,
    String? fcmToken,
    RideSortPreference? rideSortPreference,
    DateTime? createdAt,
    DateTime? updatedAt,
    DateTime? lastActiveAt,
  }) {
    return UserProfile(
      id: id ?? this.id,
      email: email ?? this.email,
      phone: phone ?? this.phone,
      name: name ?? this.name,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      userType: userType ?? this.userType,
      isDriver: isDriver ?? this.isDriver,
      language: language ?? this.language,
      defaultPaymentMethod: defaultPaymentMethod ?? this.defaultPaymentMethod,
      walletBalance: walletBalance ?? this.walletBalance,
      totalRides: totalRides ?? this.totalRides,
      totalShipments: totalShipments ?? this.totalShipments,
      totalSpent: totalSpent ?? this.totalSpent,
      points: points ?? this.points,
      level: level ?? this.level,
      isActive: isActive ?? this.isActive,
      isVerified: isVerified ?? this.isVerified,
      fcmToken: fcmToken ?? this.fcmToken,
      rideSortPreference: rideSortPreference ?? this.rideSortPreference,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      lastActiveAt: lastActiveAt ?? this.lastActiveAt,
    );
  }
}

enum UserType {
  passenger('passenger'),
  sender('sender'),
  driver('driver'),
  admin('admin');

  final String value;
  const UserType(this.value);

  static UserType fromString(String value) {
    return UserType.values.firstWhere(
      (e) => e.value == value,
      orElse: () => UserType.passenger,
    );
  }
}

enum PaymentMethod {
  cash('cash'),
  wallet('wallet'),
  card('card');

  final String value;
  const PaymentMethod(this.value);

  static PaymentMethod fromString(String value) {
    return PaymentMethod.values.firstWhere(
      (e) => e.value == value,
      orElse: () => PaymentMethod.cash,
    );
  }
}

enum UserLevel {
  bronze('bronze'),
  silver('silver'),
  gold('gold'),
  platinum('platinum');

  final String value;
  const UserLevel(this.value);

  static UserLevel fromString(String value) {
    return UserLevel.values.firstWhere(
      (e) => e.value == value,
      orElse: () => UserLevel.bronze,
    );
  }
}

/// Ride sorting preference - how to select the best price option
enum RideSortPreference {
  lowestPrice('lowest_price', 'أقل سعر', 'Lowest Price'),
  bestService('best_service', 'أفضل خدمة', 'Best Service'),
  fastestArrival('fastest_arrival', 'أسرع وصول', 'Fastest Arrival');

  final String value;
  final String labelAr;
  final String labelEn;

  const RideSortPreference(this.value, this.labelAr, this.labelEn);

  String getLabel(String language) => language == 'ar' ? labelAr : labelEn;

  static RideSortPreference fromString(String value) {
    return RideSortPreference.values.firstWhere(
      (e) => e.value == value,
      orElse: () => RideSortPreference.lowestPrice,
    );
  }
}
