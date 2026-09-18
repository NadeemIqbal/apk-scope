// Milestone 10 (Security Audit), Phase 10.3, MS10-BUILD01 — real compiled classes with deliberately
// short, generated-looking simple names (`a`, `b`, `c`, ... — the classic ProGuard/R8 short-name
// convention), hand-authored rather than produced by an actual obfuscator run. This is an honest,
// real fixture for `BuildObfuscationAnalyzer`'s naming-pattern *heuristic*: the analyzer only ever
// measures whether a compiled class's simple name matches a short/generated-looking pattern — it
// cannot and does not distinguish "R8 renamed this" from "the source author chose a one-letter name"
// (a real, permanent limitation of any purely-name-based heuristic, disclosed in the analyzer's own
// finding text, not hidden by this fixture's construction). None of these classes are ever
// instantiated or referenced anywhere in this app.
package com.apksandbox.obfuscatedfixture

class a { fun x(): Int = 1 }
class b { fun x(): Int = 2 }
class c { fun x(): Int = 3 }
class d { fun x(): Int = 4 }
class e { fun x(): Int = 5 }
class f { fun x(): Int = 6 }
class g { fun x(): Int = 7 }
class h { fun x(): Int = 8 }
class i { fun x(): Int = 9 }
class j { fun x(): Int = 10 }
