# Contributing to Twitter RSS+Webscrape Library

Thank you for your interest in contributing to the Twitter RSS+Webscrape Library! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Testing](#testing)
- [Code Style](#code-style)
- [Submitting Changes](#submitting-changes)
- [Reporting Issues](#reporting-issues)

## Code of Conduct

This project adheres to a code of conduct that all contributors are expected to follow. Please be respectful and constructive in all interactions.

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR-USERNAME/lib-common-twitterscrape.git
   cd lib-common-twitterscrape
   ```
3. **Add the upstream repository** as a remote:
   ```bash
   git remote add upstream https://github.com/ORIGINAL-OWNER/lib-common-twitterscrape.git
   ```

## Development Setup

### Prerequisites

- Android Studio (latest stable version)
- JDK 21 or higher
- Kotlin 2.1+
- Gradle 8.1+

### Build the Project

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### Run the Demo App

1. Open the project in Android Studio
2. Select the `example-app` configuration
3. Run on an emulator or physical device

## Making Changes

### Before You Start

1. **Check existing issues** to see if someone is already working on it
2. **Create a new issue** if one doesn't exist to discuss your proposed changes
3. **Create a feature branch** from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```

### Development Guidelines

- **Write clean, readable code** following Kotlin best practices
- **Add KDoc comments** for all public APIs
- **Include unit tests** for new functionality
- **Update documentation** (README, CONTRIBUTING, etc.) as needed
- **Keep commits atomic** and write clear commit messages

### Commit Message Format

```
type(scope): Brief description

Detailed description of the change (if needed).

Fixes #123
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `test`: Adding or updating tests
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `style`: Code style changes (formatting, etc.)
- `chore`: Build process or auxiliary tool changes

**Examples:**
```
feat(api): Add support for fetching tweet replies

fix(cache): Resolve cache expiry calculation bug

docs(readme): Update installation instructions
```

## Testing

### Writing Tests

- Place unit tests in `src/test/java/`
- Use JUnit 4 for test framework
- Follow the naming convention: `ClassNameTest.kt`
- Test method names should clearly describe what is being tested

Example:
```kotlin
@Test
fun `getUserTweets returns tweets for valid username`() {
    // Arrange
    val username = "testuser"
    
    // Act
    val result = repository.getUserTweets(username)
    
    // Assert
    assertTrue(result.isSuccess)
}
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :lib-common-twitterscrape:test

# Run with coverage
./gradlew test jacocoTestReport
```

## Code Style

### Kotlin Style Guide

This project follows the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).

Key points:
- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Use trailing commas in multi-line constructions
- Prefer expression body for simple functions
- Use meaningful variable and function names

### KDoc Style

All public APIs must have KDoc comments:

```kotlin
/**
 * Fetches tweets for a specified Twitter user.
 * 
 * This method retrieves tweets from Nitter instances with automatic
 * fallback to alternative sources if one fails.
 * 
 * @param username The Twitter username (without @ symbol)
 * @param maxTweets Maximum number of tweets to retrieve
 * @return Result containing list of tweets or error
 * 
 * @throws IllegalArgumentException if username is empty
 * @see Tweet
 */
suspend fun getUserTweets(username: String, maxTweets: Int = 20): Result<List<Tweet>>
```

### Formatting

Use Android Studio's built-in formatter:
1. Code → Reformat Code (Ctrl+Alt+L / Cmd+Opt+L)
2. Or run: `./gradlew ktlintFormat`

## Submitting Changes

### Pull Request Process

1. **Update your branch** with the latest upstream changes:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. **Push your changes** to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

3. **Create a Pull Request** on GitHub:
   - Use a clear, descriptive title
   - Reference any related issues
   - Provide a detailed description of changes
   - Include screenshots for UI changes

4. **Address review feedback**:
   - Make requested changes
   - Push additional commits to your branch
   - Respond to comments

### Pull Request Checklist

Before submitting, ensure:

- [ ] Code builds without errors
- [ ] All tests pass
- [ ] New tests added for new functionality
- [ ] KDoc comments added for public APIs
- [ ] README updated if needed
- [ ] No merge conflicts with main branch
- [ ] Commit messages follow the format guidelines
- [ ] Code follows style guidelines

## Reporting Issues

### Bug Reports

When reporting bugs, include:

- **Clear title and description**
- **Steps to reproduce** the issue
- **Expected behavior**
- **Actual behavior**
- **Environment details** (Android version, device, library version)
- **Code samples** or error logs if applicable

### Feature Requests

When suggesting features, include:

- **Use case description**: Why is this feature needed?
- **Proposed solution**: How should it work?
- **Alternatives considered**: What other approaches did you think about?
- **Implementation ideas**: Any thoughts on how to implement it?

## Questions?

- **Check the README** and existing documentation first
- **Search existing issues** for similar questions
- **Create a new issue** with the "question" label

## License

By contributing to this project, you agree that your contributions will be licensed under the MIT License.

---

Thank you for contributing! 🎉