plugins { id("com.android.library") }

// The promoted forwarding engine — ForwardingEngine/TcpProxy/UdpNat, packet parsing, checksums,
// DestinationPolicy, EngineLimits, the DNS parser. Needs android.net.VpnService/ParcelFileDescriptor
// directly, so this is an Android library rather than a plain Kotlin/JVM module (unlike core/model
// and core/common, which core:network depends on).
android {
 namespace = "com.nadeem.apkscope.core.network"
 compileSdk = 37
 defaultConfig { minSdk = 30 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
 implementation(project(":core:model"))
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
 implementation("org.bouncycastle:bcprov-jdk18on:1.80")
 implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
 api("com.squareup.okhttp3:okhttp:4.12.0")
 testImplementation("junit:junit:4.13.2")
}
