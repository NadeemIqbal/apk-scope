package com.nadeem.apkscope.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nadeem.apkscope.sandbox.SandboxProfileSwitcher
import com.nadeem.apkscope.ui.components.BottomNavTab
import com.nadeem.apkscope.ui.screens.analysis.AnalysisScreen
import com.nadeem.apkscope.ui.screens.detail.ComponentsDetailScreen
import com.nadeem.apkscope.ui.screens.detail.ManifestDetailScreen
import com.nadeem.apkscope.ui.screens.detail.PermissionsDetailScreen
import com.nadeem.apkscope.ui.screens.findings.FindingsScreen
import com.nadeem.apkscope.ui.screens.home.HomeScreen
import com.nadeem.apkscope.ui.screens.monitor.LiveMonitorScreen
import com.nadeem.apkscope.ui.screens.monitor.RuntimeActivityScreen
import com.nadeem.apkscope.ui.screens.report.ReportScreen
import com.nadeem.apkscope.ui.screens.reports.ReportsScreen
import com.nadeem.apkscope.ui.screens.sandbox.SandboxCleanupScreen
import com.nadeem.apkscope.ui.screens.sandbox.SandboxConfigScreen
import com.nadeem.apkscope.ui.screens.sandbox.SandboxPreparingScreen
import com.nadeem.apkscope.ui.screens.sandbox.SandboxReadyScreen
import com.nadeem.apkscope.ui.screens.settings.SettingsScreen
import com.nadeem.apkscope.ui.screens.storage.StorageScreen
import com.nadeem.apkscope.ui.screens.staticresult.StaticResultScreen
import com.nadeem.apkscope.ui.screens.staticresult.AnalysisCoverageDetailScreen
import com.nadeem.apkscope.ui.screens.poc.ApkRepackScreen
import com.nadeem.apkscope.ui.screens.tutorial.TutorialDialog
import com.nadeem.apkscope.ui.screens.tutorial.TutorialPreferences

/** The whole production graph (item 3/23): three bottom-nav destinations, plus the nested, non-tab APK analysis flow. [onOpenAdvancedDiagnostics] is the one bridge to the retained spike/debug Activities (item 4/20) — a plain Android `startActivity` call the caller supplies, kept out of this Compose graph entirely. */
@Composable
fun AppNavHost(onOpenAdvancedDiagnostics: () -> Unit) {
 val navController = rememberNavController()
 val context = LocalContext.current
 var showTutorial by remember { mutableStateOf(!TutorialPreferences.isCompleted(context)) }

 fun finishTutorial() {
  TutorialPreferences.markCompleted(context)
  showTutorial = false
 }

 if (showTutorial) {
  TutorialDialog(onFinished = ::finishTutorial)
 }

 fun bottomNav(tab: BottomNavTab) {
  val route = when (tab) {
   BottomNavTab.HOME -> HomeRoute
   BottomNavTab.REPORTS -> ReportsRoute
   BottomNavTab.SETTINGS -> SettingsRoute
  }
  navController.navigate(route) {
   popUpTo(HomeRoute) { saveState = true }
   launchSingleTop = true
   restoreState = true
  }
 }

 fun goHome() {
  navController.navigate(HomeRoute) {
   popUpTo(HomeRoute) { inclusive = false }
   launchSingleTop = true
   restoreState = true
  }
 }

 NavHost(navController = navController, startDestination = HomeRoute) {
  composable<HomeRoute> {
   HomeScreen(
    onOpenFreshAnalysis = { id -> navController.navigate(AnalysisRoute(id)) },
    onOpenCompletedAnalysis = { id -> navController.navigate(StaticResultRoute(id)) },
    onTabSelected = ::bottomNav,
   onOpenPocRepack = { navController.navigate(PocApkRepackRoute) },
   onOpenClearMemory = { navController.navigate(StorageRoute) },
   onOpenActiveSession = { id, state ->
     val destination = when (state) {
      com.nadeem.apkscope.core.model.SandboxSessionState.PREPARING,
      com.nadeem.apkscope.core.model.SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
      com.nadeem.apkscope.core.model.SandboxSessionState.INSTALLING,
      com.nadeem.apkscope.core.model.SandboxSessionState.INSTALLED -> SandboxPreparingRoute(id)
      com.nadeem.apkscope.core.model.SandboxSessionState.READY,
      com.nadeem.apkscope.core.model.SandboxSessionState.LAUNCHING -> SandboxReadyRoute(id)
      com.nadeem.apkscope.core.model.SandboxSessionState.RUNNING -> LiveMonitorRoute(id)
      com.nadeem.apkscope.core.model.SandboxSessionState.ENDING,
      com.nadeem.apkscope.core.model.SandboxSessionState.CLEARING_DATA,
      com.nadeem.apkscope.core.model.SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION,
      com.nadeem.apkscope.core.model.SandboxSessionState.CLEANUP,
      com.nadeem.apkscope.core.model.SandboxSessionState.CLEANUP_REQUIRED -> SandboxCleanupRoute(id)
      com.nadeem.apkscope.core.model.SandboxSessionState.CREATED,
      com.nadeem.apkscope.core.model.SandboxSessionState.COMPLETED,
      com.nadeem.apkscope.core.model.SandboxSessionState.FAILED,
      com.nadeem.apkscope.core.model.SandboxSessionState.CANCELLED -> null
     }
     if (destination != null) navController.navigate(destination) { launchSingleTop = true }
    },
   )
  }
  composable<ReportsRoute> {
   ReportsScreen(
    onTabSelected = ::bottomNav,
    onOpenReport = { id -> navController.navigate(ReportRoute(id)) },
    onOpenAnalysis = { id -> navController.navigate(StaticResultRoute(id, returnToHistory = true)) },
   )
  }
  composable<SettingsRoute> {
   SettingsScreen(
    onTabSelected = ::bottomNav,
    onOpenAdvancedDiagnostics = onOpenAdvancedDiagnostics,
    onOpenTrafficInspector = { navController.navigate(TrafficInspectorRoute) },
    onOpenStorage = { navController.navigate(StorageRoute) },
   )
  }
  composable<StorageRoute> {
   StorageScreen(onBack = { navController.popBackStack() })
  }

  composable<AnalysisRoute> { backStackEntry ->
   val route: AnalysisRoute = backStackEntry.toRoute<AnalysisRoute>()
   AnalysisScreen(
    sessionId = route.sessionId,
    onComplete = { id -> navController.navigate(StaticResultRoute(id)) { popUpTo(HomeRoute) { inclusive = false } } },
    onBack = { goHome() },
   )
  }
  composable<StaticResultRoute> { backStackEntry ->
   val route: StaticResultRoute = backStackEntry.toRoute()
    StaticResultScreen(
     sessionId = route.sessionId,
     onContinueToSandbox = { id -> navController.navigate(SandboxConfigRoute(id)) },
     onViewPermissions = { id -> navController.navigate(PermissionsDetailRoute(id)) },
     onViewComponents = { id -> navController.navigate(ComponentsDetailRoute(id)) },
     onViewManifest = { id -> navController.navigate(ManifestDetailRoute(id)) },
     onViewFindings = { id -> navController.navigate(FindingsRoute(id)) },
     onViewEmbeddedUrls = { id -> navController.navigate(EmbeddedUrlsRoute(id)) },
     onViewDetectedSdks = { id -> navController.navigate(DetectedSdksRoute(id)) },
     onViewApiReferences = { id -> navController.navigate(ApiReferencesRoute(id)) },
     onOpenSecurityAudit = { id -> navController.navigate(SecurityAuditRoute(id)) },
     onViewCoverage = { id -> navController.navigate(AnalysisCoverageRoute(id)) },
     onBack = {
      if (route.returnToHistory) navController.popBackStack() else goHome()
     },
    )
  }
  composable<FindingsRoute> { backStackEntry ->
   val route: FindingsRoute = backStackEntry.toRoute()
   FindingsScreen(sessionId = route.sessionId, onBack = { navController.popBackStack() })
  }
  composable<PermissionsDetailRoute> { backStackEntry ->
   val route: PermissionsDetailRoute = backStackEntry.toRoute()
   PermissionsDetailScreen(sessionId = route.sessionId, onBack = { navController.popBackStack() })
  }
  composable<ComponentsDetailRoute> { backStackEntry ->
   val route: ComponentsDetailRoute = backStackEntry.toRoute()
   ComponentsDetailScreen(sessionId = route.sessionId, onBack = { navController.popBackStack() })
  }
  composable<ManifestDetailRoute> { backStackEntry ->
   val route: ManifestDetailRoute = backStackEntry.toRoute()
   ManifestDetailScreen(sessionId = route.sessionId, onBack = { navController.popBackStack() })
  }
  composable<AnalysisCoverageRoute> { backStackEntry ->
   val route: AnalysisCoverageRoute = backStackEntry.toRoute()
   AnalysisCoverageDetailScreen(sessionId = route.sessionId, onBack = { navController.popBackStack() })
  }
  composable<SandboxConfigRoute> { backStackEntry ->
   val route: SandboxConfigRoute = backStackEntry.toRoute()
   SandboxConfigScreen(
    sessionId = route.sessionId,
    onViewStaticAnalysis = { id -> navController.navigate(StaticResultRoute(id)) },
    onPrepare = { id, stage ->
     val destination = when (stage) {
      com.nadeem.apkscope.ui.screens.sandbox.SandboxLifecycleStage.PREPARING -> SandboxPreparingRoute(id)
      com.nadeem.apkscope.ui.screens.sandbox.SandboxLifecycleStage.READY -> SandboxReadyRoute(id)
      com.nadeem.apkscope.ui.screens.sandbox.SandboxLifecycleStage.RUNNING -> LiveMonitorRoute(id)
      com.nadeem.apkscope.ui.screens.sandbox.SandboxLifecycleStage.ENDING -> SandboxCleanupRoute(id)
     }
     navController.navigate(destination) { popUpTo(StaticResultRoute(id)) { inclusive = false } }
    },
    onBack = { navController.popBackStack() },
   )
  }
  composable<SandboxPreparingRoute> { backStackEntry ->
   val route: SandboxPreparingRoute = backStackEntry.toRoute()
   SandboxPreparingScreen(
    sessionId = route.sessionId,
    onOpenWorkSandbox = { SandboxProfileSwitcher.openSandboxProfile(context) },
    // Retry root-cause fix: a Retry on a FAILED/CANCELLED session creates a genuinely fresh session
    // (the old one's own id can never make forward progress again — see the ViewModel's own doc) —
    // replace this dead screen with the new session's own Preparing screen rather than stacking it,
    // so Back from there returns to Home, not to the now-irrelevant failed attempt.
    onRetryAsNewSession = { id -> navController.navigate(SandboxPreparingRoute(id)) { popUpTo(SandboxPreparingRoute(route.sessionId)) { inclusive = true } } },
    onBack = { goHome() },
   )
  }
  composable<SandboxReadyRoute> { backStackEntry ->
   val route: SandboxReadyRoute = backStackEntry.toRoute()
   SandboxReadyScreen(
    sessionId = route.sessionId,
    onBack = { goHome() },
   )
  }
  composable<LiveMonitorRoute> { backStackEntry ->
   val route: LiveMonitorRoute = backStackEntry.toRoute()
   LiveMonitorScreen(
    sessionId = route.sessionId,
    onEnded = { id -> navController.navigate(SandboxCleanupRoute(id)) { popUpTo(HomeRoute) { inclusive = false } } },
    onBack = { goHome() },
   )
  }
  composable<SandboxCleanupRoute> { backStackEntry ->
   val route: SandboxCleanupRoute = backStackEntry.toRoute()
   SandboxCleanupScreen(
    sessionId = route.sessionId,
    onDone = { goHome() },
    onBack = { goHome() },
    onViewRuntimeActivity = { id -> navController.navigate(RuntimeActivityRoute(id)) },
    onViewReport = { id -> navController.navigate(ReportRoute(id)) { popUpTo(HomeRoute) { inclusive = false } } },
   )
  }
  composable<RuntimeActivityRoute> { backStackEntry ->
   val route: RuntimeActivityRoute = backStackEntry.toRoute()
   RuntimeActivityScreen(sessionId = route.sessionId, onBack = { navController.popBackStack() })
  }
  composable<ReportRoute> { backStackEntry ->
   val route: ReportRoute = backStackEntry.toRoute()
   ReportScreen(
    sessionId = route.sessionId,
    onDone = { goHome() },
    onBack = { navController.popBackStack() },
   )
  }
  composable<TrafficInspectorRoute> {
   com.nadeem.apkscope.ui.screens.traffic.TrafficInspectorScreen(
    onBack = { navController.popBackStack() }
   )
  }
  composable<EmbeddedUrlsRoute> { backStackEntry ->
   val route: EmbeddedUrlsRoute = backStackEntry.toRoute()
   com.nadeem.apkscope.ui.screens.staticresult.EmbeddedUrlsDetailScreen(
    sessionId = route.sessionId,
    onBack = { navController.popBackStack() }
   )
  }
  composable<DetectedSdksRoute> { backStackEntry ->
   val route: DetectedSdksRoute = backStackEntry.toRoute()
   com.nadeem.apkscope.ui.screens.staticresult.DetectedSdksDetailScreen(
    sessionId = route.sessionId,
    onBack = { navController.popBackStack() }
   )
  }
  composable<ApiReferencesRoute> { backStackEntry ->
   val route: ApiReferencesRoute = backStackEntry.toRoute()
   com.nadeem.apkscope.ui.screens.staticresult.ApiReferencesDetailScreen(
    sessionId = route.sessionId,
    onBack = { navController.popBackStack() }
   )
  }
  composable<SecurityAuditRoute> { backStackEntry ->
   val route: SecurityAuditRoute = backStackEntry.toRoute()
   com.nadeem.apkscope.ui.screens.securityaudit.SecurityAuditScreen(
    sessionId = route.sessionId,
    onBack = { navController.popBackStack() },
    onOpenFinding = { id, ruleId -> navController.navigate(SecurityAuditFindingDetailRoute(id, ruleId)) },
   )
  }
  composable<SecurityAuditFindingDetailRoute> { backStackEntry ->
   val route: SecurityAuditFindingDetailRoute = backStackEntry.toRoute()
   com.nadeem.apkscope.ui.screens.securityaudit.SecurityAuditFindingDetailScreen(
    sessionId = route.sessionId,
    ruleId = route.ruleId,
    onBack = { navController.popBackStack() },
   )
  }

  // APK patching and Frida-based traffic instrumentation.
  composable<PocApkRepackRoute> {
   ApkRepackScreen(
    onBack = { navController.popBackStack() },
    // Frida inspection is an action flow, not a second static-analysis detour. The static
    // analysis is still persisted by the repack screen; the user goes straight to sandbox setup.
    onOpenAnalysis = { id -> navController.navigate(SandboxConfigRoute(id)) {
     popUpTo(PocApkRepackRoute) { inclusive = true }
    } },
   )
  }
 }
}
