# teamchong

Commits per month:

```
$ python3 script/collect-commit-per-month.py

============================================================
Developer: @teamchong
  10 repos found for user
  oct-2025: 0
  nov-2025: 847  (teamchong/metal0:847)
  dec-2025: 3147  (teamchong/metal0:1775, teamchong/edgebox:756, teamchong/lanceql:452, teamchong/zell:164)
  jan-2026: 1283  (teamchong/metal0:153, teamchong/edgebox:348, teamchong/lanceql:357, teamchong/termweb:417, teamchong/zell:5, bytecodealliance/wasm-micro-runtime:2, ghostty-org/ghostty:1)
  feb-2026: 522  (teamchong/edgebox:53, teamchong/termweb:306, teamchong/vectorjson:154, teamchong/codesift:9)
  mar-2026: 8  (teamchong/edgebox:2, teamchong/edgeq:6)
```

Something special seems to have happened in Dec 2025 and Jan 2026, tapering off afterwards. This coincides with an active development period of the `teamchong/metal0` repository, which seems to have stopped development in Jan 2026.

Commits per day in December 2025:

```
$ python3 script/collect-commits-per-day.py --developer teamchong --month 2025-12
Collecting commits for @teamchong  2025-12  (31 days)
  10 repo(s) from developers.json: teamchong/metal0, teamchong/edgebox, teamchong/lanceql, teamchong/termweb, teamchong/zell, teamchong/vectorjson, teamchong/codesift, teamchong/edgeq, bytecodealliance/wasm-micro-runtime, ghostty-org/ghostty
  2025-12-01   55 commits
  2025-12-02  188 commits
  2025-12-03   92 commits
  2025-12-04   20 commits
  2025-12-05  111 commits
  2025-12-06   25 commits
  2025-12-07   99 commits
  2025-12-08   63 commits
  2025-12-09  114 commits
  2025-12-10  197 commits
  2025-12-11  239 commits
  2025-12-12  258 commits
  2025-12-13  186 commits
  2025-12-14  111 commits
  2025-12-15  159 commits
  2025-12-16  100 commits
  2025-12-17  295 commits
  2025-12-18  139 commits
  2025-12-19   82 commits
  2025-12-20   62 commits
  2025-12-21   35 commits
  2025-12-22   27 commits
  2025-12-23   13 commits
  2025-12-24    3 commits
  2025-12-25   20 commits
  2025-12-26   93 commits
  2025-12-27   66 commits
  2025-12-28   64 commits
  2025-12-29  115 commits
  2025-12-30   99 commits
  2025-12-31   17 commits
```

Commits per day in January 2026:

```
$ python3 script/collect-commits-per-day.py --developer teamchong --month 2026-01
Collecting commits for @teamchong  2026-01  (31 days)
  10 repo(s) from developers.json: teamchong/metal0, teamchong/edgebox, teamchong/lanceql, teamchong/termweb, teamchong/zell, teamchong/vectorjson, teamchong/codesift, teamchong/edgeq, bytecodealliance/wasm-micro-runtime, ghostty-org/ghostty
  2026-01-01   42 commits
  2026-01-02  217 commits
  2026-01-03   33 commits
  2026-01-04   15 commits
  2026-01-05   10 commits
  2026-01-06   12 commits
  2026-01-07    6 commits
  2026-01-08   38 commits
  2026-01-09   27 commits
  2026-01-10   78 commits
  2026-01-11   50 commits
  2026-01-12   43 commits
  2026-01-13   84 commits
  2026-01-14   21 commits
  2026-01-15   12 commits
  2026-01-16    7 commits
  2026-01-17    6 commits
  2026-01-18   23 commits
  2026-01-19   25 commits
  2026-01-20   41 commits
  2026-01-21   75 commits
  2026-01-22   71 commits
  2026-01-23   69 commits
  2026-01-24   33 commits
  2026-01-25   58 commits
  2026-01-26   45 commits
  2026-01-27    8 commits
  2026-01-28   11 commits
  2026-01-29   45 commits
  2026-01-30   62 commits
  2026-01-31   16 commits
```

# Agent-Supported Work

Surprisingly, an agent was not credited with supporting this work, but the pace of development does not seem to be something that a human could do alone. For example, below is a snippet of a roughly two-hour period of commit activity on the `teamchong/metal0` repo:

```
$ git log --since=2025-12-09 --until=2025-12-10 --pretty=format:"Commit: %H%nAuthor Date: %ad%n%b" --stat

---8<---SNIP---8<---
Commit: 1a5108ad7e043c0b78ec1cf2ddef0226c50fc781
Author Date: Tue Dec 9 11:04:44 2025 -0500
Nested lists now correctly generate as ArrayListUnmanaged at all levels.

Key fixes:
1. Type inference: Always use list type for list literals, never array
   - Array type [N]T caused mismatches with codegen which produces ArrayList
   - Nested lists especially broke because inner arrays didn't match outer ArrayList

2. Codegen: Track inside_list_depth to prevent array literal optimization
   - inner lists inside outer lists must be ArrayLists
   - Added depth tracking in genArrayListInit for assignment-based list init

3. Skip comptime path for lists containing nested lists
   - InferListType can't handle ArrayList element types correctly

Test case now compiles correctly:
    a = [[0, 1], [2, 3]]  # ArrayList(ArrayList(i64))
    b = [[[1,2],[3,4]], [[5,6],[7,8]]]  # 3 levels

Note: Subscript access (a[0][0]) still needs .items access fix.

 src/analysis/native_types/expressions.zig          | 26 ++++++++-------------
 src/codegen/native/expressions/collections.zig     | 27 +++++++++++++++++++---
 src/codegen/native/main/core.zig                   |  5 ++++
 .../native/statements/assign/value_generation.zig  |  5 ++++
 4 files changed, 44 insertions(+), 19 deletions(-)

Commit: 9c52d324b0f119895e248742c289d0353ed40661
Author Date: Tue Dec 9 10:44:17 2025 -0500
Overcomes Zig's struct scoping limitation where structs cannot capture
function-local variables. Instead of pre-generating complex class attrs
outside the struct definition, we now generate them as lazy-computed
getter methods with threadlocal caching inside the struct.

Key changes:
- Pre-generate closure types at struct level for lambda comprehensions
- Generate lazy getter methods with threadlocal caching
- Track lazy class attrs for proper accessor transformation (C.attr -> (try C.attr(__alloc)))
- Generate actual lambda body expressions in closure impl
- Handle comprehensions iterating over closure lists (x() calls)

Python code like this now compiles correctly:
    class C:
        items = [(lambda i=i: i * 2) for i in range(3)]
        doubled = [x() for x in items]

The lazy-computed pattern preserves Python's "compute once at class
definition" semantics while working within Zig's type system constraints.

 src/codegen/native/expressions/misc.zig            |  14 +
 src/codegen/native/main/cleanup.zig                |   4 +
 src/codegen/native/main/core.zig                   |   5 +
 .../native/statements/functions/generators.zig     | 329 ++++++++++++++++++---
 4 files changed, 308 insertions(+), 44 deletions(-)

Commit: 03e5d75b4470d3321fdeb3b88184062fa1ce8670
Author Date: Tue Dec 9 10:10:21 2025 -0500
When iterating over a list of closures, the loop variable needs to be
registered as a closure so that calls use x.call() instead of x().

Changes:
- Add closure_list_vars map to track variables holding lists of closures
- Mark lambda comprehension results as closure lists
- Check closure_list_vars when iterating to register loop var as closure
- Update type inferrer to return .closure for lambda list comprehension elements

The closure call syntax is now correctly generated:
  for (items) |x| { x.call() }  // not x()

Known limitation: Class-level closure list attributes have Zig scope issues
when the class is defined inside a function (struct can't capture func-local vars).

 src/analysis/native_types/expressions.zig          |  8 ++++-
 src/codegen/native/expressions/comprehensions.zig  | 35 ++++++++++++++++++++++
 src/codegen/native/main/cleanup.zig                |  4 +++
 src/codegen/native/main/core.zig                   |  4 +++
 .../native/statements/functions/generators.zig     | 11 +++++++
 5 files changed, 61 insertions(+), 1 deletion(-)

Commit: 36e63c1996461317ae3b65bfc9d83ec5662fdebb
Author Date: Tue Dec 9 09:59:49 2025 -0500
For comprehensions like `[(lambda: i) for i in range(5)]`, generate
the closure type ONCE before the loop and instantiate it in the loop:

1. Generate __CaptureType_N = struct { i: i64 };
2. Generate __ClosureImpl_N = struct { fn call(...) ... };
3. Generate __ClosureType_N = runtime.Closure0(__CaptureType_N, ...);
4. Use __ClosureType_N as ArrayListUnmanaged element type
5. Instantiate with __ClosureType_N{ .captures = .{ .i = value } }

This ensures all closure instances have the same Zig type and can be
stored in a homogeneous container.

TODO: Variable type inference needs update to use closure type
TODO: Closure call generation needs x.call() instead of x()

 src/codegen/native/expressions/comprehensions.zig | 92 ++++++++++++++++++++++-
 1 file changed, 91 insertions(+), 1 deletion(-)

Commit: deb57eb65a9158fa03d0b078f11edba0bf810438
Author Date: Tue Dec 9 09:46:00 2025 -0500
Class-level attributes with complex expressions (list comprehensions, calls)
were causing 'try outside function scope' errors because they were being
generated inside the struct definition where try is invalid.

Changes:
- Pre-generate complex class attributes as module-level variables before struct
- Reference pre-generated vars inside struct as pub const
- Add var_renames for pre-generated attrs so subsequent exprs reference correctly
- Add comprehension loop vars to var_renames for lambda closure generation
- Fix lambda default parameter capture to include default value expressions
- Apply var_renames lookup in all closure struct initialization sites

This allows class body assignments like `items = [(lambda i=i: i) for i in range(5)]`
to compile correctly by hoisting the complex expression before the struct.

 src/codegen/native/expressions/comprehensions.zig  |   2 +
 src/codegen/native/expressions/lambda.zig          |   8 ++
 src/codegen/native/expressions/lambda_closure.zig  |  21 ++--
 .../native/statements/functions/generators.zig     | 119 +++++++++++++++++++++
 4 files changed, 143 insertions(+), 7 deletions(-)

Commit: 01750ae5e94530f862d6bdab122d41c3c84d1320
Author Date: Tue Dec 9 09:16:36 2025 -0500
For parameters with dict type inferred from call sites and =None default,
use anytype instead of a specific dict type. This fixes polymorphic
dict parameters where different call sites pass dicts with different
value types (e.g., {"x": [1]} vs {"y": [1,2,3,4,5]}).

Changes:
- Add dict+None default check in getTypeFromCallSiteOrScope
- Fix method index offset (func.args includes 'self', call_arg_types doesn't)
- Add same check in genMethodSignatureWithSkip for class methods
- Add same check in getVarTypeInScope path

This reduces test_list errors from 49 to 13.

 .../statements/functions/generators/signature.zig  | 44 ++++++++++++++++++++--
 1 file changed, 41 insertions(+), 3 deletions(-)
---8<---SNIP---8<---
```

The pace at which commits are being produced, the size of those commits, and the length of the associated commit messages suggest that development is supported by AI and likely by agents.
