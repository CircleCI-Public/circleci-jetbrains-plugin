# Static Analysis Tools

This project includes multiple static analysis tools to ensure code quality, security, and maintainability.

## Quick Start

```bash
# Run all static analysis tools
task lint

# Or using Gradle
./gradlew staticAnalysis
```

## Available Tools

### 1. **detekt** - Kotlin Static Analysis

Analyzes Kotlin code for code smells, complexity issues, and potential bugs.

**Run:**
```bash
task detekt
# or
./gradlew detekt
```

**Report:** `build/reports/detekt/detekt.html`

**Configuration:** `config/detekt/detekt.yml`

**Key Features:**
- Complexity analysis
- Code smell detection
- Naming convention checks
- Potential bug detection
- Performance issue identification

**Common Issues:**
- Complex methods (>15 lines)
- Long parameter lists (>6 parameters)
- Too many functions in a class
- Unused imports/code

### 2. **ktlint** - Kotlin Code Style

Enforces Kotlin coding conventions and style guidelines.

**Check:**
```bash
task ktlint
# or
./gradlew ktlintCheck
```

**Auto-fix:**
```bash
task ktlint-format
# or
./gradlew ktlintFormat
```

**Report:** `build/reports/ktlint/`

**Configuration:** `.editorconfig`

**Key Features:**
- Automatic code formatting
- Kotlin style guide enforcement
- Import organization
- Whitespace and indentation checks

### 3. **OWASP Dependency-Check** - Security Vulnerability Scanning

Scans project dependencies for known security vulnerabilities from the National Vulnerability Database (NVD).

**Run:**
```bash
task security-scan
# or
./gradlew dependencyCheckAnalyze
```

**Report:** `build/reports/dependency-check-report.html`

**Configuration:** `config/owasp-suppressions.xml`

**Key Features:**
- CVE vulnerability detection
- Dependency security analysis
- Severity ratings (Critical, High, Medium, Low)
- False positive suppression

**Setup (Optional):**

For faster scans, get an NVD API key:
1. Visit https://nvd.nist.gov/developers/request-an-api-key
2. Set environment variable: `export NVD_API_KEY=your-key-here`

### 4. **IntelliJ Plugin Verifier**

Verifies plugin compatibility with IntelliJ Platform versions.

**Run:**
```bash
task verify
# or
./gradlew verifyPlugin
```

**Key Checks:**
- API compatibility
- Deprecated API usage
- Plugin structure validation
- IDE version compatibility

## Integration with CI/CD

Add to your CI pipeline:

```yaml
# Example .circleci/config.yml
jobs:
  static-analysis:
    steps:
      - run:
          name: Static Analysis
          command: ./gradlew staticAnalysis
      - store_artifacts:
          path: build/reports
```

## Reports

All reports are generated in `build/reports/`:

```
build/reports/
├── detekt/
│   ├── detekt.html          # Main detekt report
│   ├── detekt.xml           # XML format
│   └── detekt.sarif         # SARIF format (GitHub compatible)
├── ktlint/
│   ├── ktlintMainSourceSetCheck.html
│   └── ktlintTestSourceSetCheck.html
├── dependency-check-report.html
└── dependency-check-report.json
```

## Configuration Files

### detekt Configuration

Edit `config/detekt/detekt.yml` to customize rules:

```yaml
complexity:
  ComplexMethod:
    threshold: 15  # Adjust complexity threshold
  LongMethod:
    threshold: 60  # Adjust method length
```

### ktlint Configuration

Edit `.editorconfig`:

```ini
[*.{kt,kts}]
max_line_length = 120
indent_size = 4
```

### OWASP Suppressions

Edit `config/owasp-suppressions.xml` to suppress false positives:

```xml
<suppress>
    <notes><![CDATA[
    False positive - CVE doesn't apply to our usage
    ]]></notes>
    <cve>CVE-2021-12345</cve>
</suppress>
```

## Baseline Management

### detekt Baseline

If detekt finds too many issues to fix immediately, create a baseline:

```bash
./gradlew detektBaseline
```

This creates `config/detekt/baseline.xml` with current issues, allowing you to:
- Focus on fixing new issues
- Gradually improve existing code
- Track technical debt

**Update baseline:**
```bash
./gradlew detektBaseline
```

## Pre-commit Hook (Optional)

Add to `.git/hooks/pre-commit`:

```bash
#!/bin/bash
echo "Running ktlint..."
./gradlew ktlintCheck

if [ $? -ne 0 ]; then
    echo "❌ ktlint failed. Run 'task ktlint-format' to auto-fix."
    exit 1
fi

echo "✅ Code style checks passed"
```

Make executable:
```bash
chmod +x .git/hooks/pre-commit
```

## Troubleshooting

### detekt: Too many issues

```bash
# Create a baseline to track existing issues
./gradlew detektBaseline

# Or disable specific rules in config/detekt/detekt.yml
```

### ktlint: Auto-format failed

```bash
# Format code automatically
task ktlint-format

# Then review and commit changes
git diff
```

### OWASP: False positives

```bash
# Add suppressions in config/owasp-suppressions.xml
# Then re-run
task security-scan
```

### Slow dependency-check scans

```bash
# Get an NVD API key (see above)
export NVD_API_KEY=your-key-here

# Or disable if not needed
# (Comment out in build.gradle.kts)
```

## Best Practices

1. **Run locally before committing**
   ```bash
   task lint
   ```

2. **Fix issues incrementally**
   - Use baselines for existing issues
   - Fix new issues immediately
   - Gradually reduce technical debt

3. **Review reports regularly**
   - Check HTML reports for details
   - Address critical security vulnerabilities first
   - Fix code smells that impact maintainability

4. **Configure for your team**
   - Adjust thresholds in detekt.yml
   - Customize ktlint rules in .editorconfig
   - Document suppressions in OWASP config

5. **Integrate with CI/CD**
   - Run on every PR
   - Block merges on critical issues
   - Generate trend reports

## Resources

- [detekt Documentation](https://detekt.dev/)
- [ktlint Documentation](https://pinterest.github.io/ktlint/)
- [OWASP Dependency-Check](https://jeremylong.github.io/DependencyCheck/)
- [IntelliJ Plugin Verifier](https://github.com/JetBrains/intellij-plugin-verifier)
