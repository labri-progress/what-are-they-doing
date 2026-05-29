//> using scala 3.8.3
//> using native-version 0.5.10
//> using js-version 1.21.0

// specifies to use the installed jvm, or error if none is present
// otherwise the default is to always download a jdk, even if one is installed (except if JAVA_HOME is set explicitly)
// check if this is overridden in project-local.scala
//> using jvm system

// for compiler options:
// see https://docs.scala-lang.org/overviews/compiler-options/
// and https://docs.scala-lang.org/scala3/guides/migration/options-new.html
// and https://www.scala-lang.org/api/current/scala/language$.html
// and run: cs launch scala3-compiler -- -help
// and run: cs launch scala3-compiler -- -W

// 1) changes the available JDK APIs,
// 2) sets the classfile version (and maybe enables some associated classfile features)
// should generally be set to something, otherwise all features from the randomly selected current JDK are available
// check if this is overridden in project-local.scala
//> using option -java-output-version 25

// workarounds for deprecated JVM features introduced in JDK 25, these are needed until all dependencies are built with 3.8
//> using java-options -XX:+IgnoreUnrecognizedVMOptions --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED

// Spell out feature and deprecation warnings instead of summarizing them into a single warning
// always turn this on to make the compiler less ominous
//> using option -feature -deprecation

// treat warnings as errors
// generally, adressing warnings as they come up is much less work than fixing problems later
// do consider disabling for migrations and large refactorings to allow those changes to happen in smaller steps
//> using option -Werror

// Warn when a comment ambiguously assigned to multiple enum cases is discarded.
//> using option -Wenum-comment-discard

// Warn if comparison with a pattern value looks like it might always fail.
//> using option -Wimplausible-patterns

// can be annoying with methods that have optional results, can also help with methods that have non optional results …
//!> using option -Wnonunit-statement

// this prevents recursive calls that use any of the default parameters.
// the hope is, that this allows to have some accumulater default to empty, but then not forget to update it during recursio
//> using option -Wrecurse-with-default

// checks that objects are fully initialized before they are accessed
// is kinda likely to cause strange compiler crashes, disable if something is strange
// (was -Ysafe-init for scala 3.4 and below)
//> using option -Wsafe-init

// type parameter shadowing often is accidental, and especially for short type names keeping them separate seems good
//> using option -Wshadow:type-parameter-shadow

// shadowing fields causes names inside and outside of the class to resolve to different things, and is quite weird.
// however, this has some kinda false positives when subclasses pass parameters to superclasses.
//> using option -Wshadow:private-shadow

// Warn a standard interpolator used toString on a reference type.
// This seems way over the top, what else would one use interpolators for?
//!> using option -Wtostring-interpolated

// reports methods that have public forwarders (in the binaries) because they are accessed by an inline function
//> using option -WunstableInlineAccessors

// seems generally unobtrusive (just add some explicit ()) and otherwise helpful
//> using option -Wvalue-discard

// Warn if function arrow was used instead of context literal ?=>.
// Does not work well with mixed paramaters.
//!> using option -Wwrong-arrow

// makes Null no longer be a sub type of all subtypes of AnyRef
// since Scala 3.5 uses special return types for Java methods, see https://github.com/scala/scala3/pull/17369
// disable special handling with -Yno-flexible-types
//> using option -Yexplicit-nulls

//////////// Unused warnings

// make unused warnings not warnings but just infos as they rarely indicate problems
//> using option "-Wconf:id=E198:info"

// Warn for unused @nowarn annotations
//> using option -Wunused:nowarn

// On 3.7 & 3.8 this enables: -Wunused:imports,privates,locals,implicits,
//> using option -Wunused:linted

// Explicit parameters … maybe also a good idea?
//> using option "-Wunused:explicits"

// pattern variables are useful as documentation of what was destructured, and it seems unlikely that they indicate a bug or design problem
//!> using option "-Wunused:patvars"

//////////// Tests

// munit is an excellent compromise on simplicity with a couple of simple features
//> using test.dep org.scalameta::munit::1.3.1
//> using test.dep org.scalameta::munit-scalacheck::1.3.0
