# CircleCI IntelliJ Plugin Architecture

## Overview

This document describes the architecture patterns and conventions used in the CircleCI IntelliJ plugin.

## Core Architecture Patterns

### 1. Service Layer Pattern

The plugin uses IntelliJ's service system for dependency injection and lifecycle management.

**Service Levels:**
- `@Service(Service.Level.APP)` - Application-wide singleton (e.g., `CircleCIApiService`)
- `@Service(Service.Level.PROJECT)` - One instance per project (e.g., `JobDataService`, `CircleCIStateStore`)

**Service Registration:**
Services are registered in `plugin.xml`:
```xml
<projectService serviceImplementation="com.circleci.idea.job.JobDataService"/>
<applicationService serviceImplementation="com.circleci.idea.api.CircleCIApiService"/>
```

**Service Access:**
```kotlin
val jobDataService = project.getService(JobDataService::class.java)
val apiService = CircleCIApiService.getInstance()
```

### 2. State Management

**Reactive State with Kotlin Flow:**
- Central state store: `CircleCIStateStore` (project-scoped service)
- Uses `StateFlow` for reactive state updates
- Components subscribe to state changes and update UI accordingly

**State Structure:**
```kotlin
interface CircleCIState {
    val auth: StateFlow<AuthState>
    val projects: StateFlow<ProjectsState>
    val projectsData: StateFlow<ProjectsDataState>
    val config: StateFlow<ConfigState>
    val filters: StateFlow<FiltersState>
    val ui: StateFlow<UIState>
}
```

**State Update Pattern:**
```kotlin
private val _projectsData = MutableStateFlow(ProjectsDataState())
val projectsData: StateFlow<ProjectsDataState> = _projectsData.asStateFlow()

fun updateJobs(projectSlug: String, workflowId: String, jobs: List<Job>) {
    _projectsData.update { state ->
        // Update state immutably
    }
}
```

### 3. Data Service Layer

**Pattern:**
- Services like `RunListService` (runs, workflows and jobs from the V3 API) and `JobDetailsService`
- Handle fetching data from API, applying the user's filters
- Convert API models to domain models (`RunMapper` for the V3 run/workflow/job wire types)
- Provide helper methods for data operations

**Example:**
```kotlin
@Service(Service.Level.PROJECT)
class JobDataService(private val project: Project) {
    private val stateStore = project.getService(CircleCIStateStore::class.java)
    private val apiService = CircleCIApiService.getInstance()

    suspend fun fetchJobs(projectSlug: String, workflowId: String): List<Job> {
        val result = apiService.getJobs(workflowId)
        return result.fold(
            onSuccess = { response ->
                val jobs = response.items.map { convertToJob(it) }
                stateStore.updateJobs(projectSlug, workflowId, jobs)
                jobs
            },
            onFailure = { error ->
                logger.error("Failed to fetch jobs", error)
                emptyList()
            }
        )
    }
}
```

### 4. API Client Architecture

**Two-Layer Design:**

1. **Low-Level Client (`CircleCIApiClient`):**
   - HTTP client using OkHttp3
   - Retry logic with exponential backoff
   - Rate limiting (token bucket)
   - Request deduplication
   - Returns `ApiResponse` sealed class

2. **Service Layer (`CircleCIApiService`):**
   - Typed methods for each endpoint
   - JSON parsing with Gson
   - Returns `Result<T>` types
   - Converts `ApiResponse` to `Result`

**Usage Pattern:**
```kotlin
fun getJob(client: CircleCIApiClient, jobId: String): Result<JobDetailWire> {
    return executeRequest(client, "/api/v3/jobs/$jobId") { data ->
        gson.fromJson<V3Entity<JobDetailWire>>(data, object : TypeToken<V3Entity<JobDetailWire>>() {}.type).data
            ?: error("No job $jobId")
    }
}
```

Everything uses the V3 API (`/api/v3/...`) except approving a hold and
canceling a job, which have no V3 endpoint yet and stay on V2. V3 ignores
request fields it doesn't know, so check field names against the V3 handler
(`public-api-service/v3`) or the CircleCI CLI's `internal/apiclient`, not V2's.

### 5. UI Component Patterns

#### Tool Window Pattern

**Factory:** `CircleCIToolWindowFactory`
- Implements `ToolWindowFactory`
- Creates tool window on first open
- Registers content via `ContentFactory`

**Content:** `CircleCIToolWindowContent`
- Implements `Disposable`
- Uses `CoroutineScope` with `SupervisorJob + Dispatchers.Main`
- Layout: `JBPanel<JBPanel<*>>(BorderLayout())`
  - North: the run filters (`RunFilterBar`, the IDE's Pull Requests drop-downs)
  - Center: the run tree, in a `JewelComposePanel`
- Until you log in, `SignedOutView` (Compose) takes the runs' place

**Disposal:**
```kotlin
override fun dispose() {
    scope.cancel()
}
```

#### Tree View Pattern

**Components:**
- `CircleCITreeModel` - Holds the nodes, loads them asynchronously, and keeps what's open and selected in a Jewel `TreeState`
- `CircleCITreeNode` - Sealed class for node types
- `RunTree.kt` - Builds Jewel's tree from the nodes, keyed by run, workflow or job id
- `RunTreeView` - Draws it with Jewel's `LazyTree`, with the IDE's actions on right-click

**Async Loading:** what's open is kept by key, not by node, so it carries
over to the nodes a reload rebuilds. Whenever it changes, or nodes load,
the open nodes with nothing loaded yet load their children:
```kotlin
snapshotFlow { treeState.openNodes }.collect {
    nodesToLoad(root, it).forEach { node -> loadChildren(node, refresh = false) }  // Async
}
```

**Node Types:**
```kotlin
sealed class CircleCITreeNode {
    class RootNode : CircleCITreeNode()
    class RunNode(var run: Run) : CircleCITreeNode()
    class WorkflowNode(var workflow: Workflow) : CircleCITreeNode()
    class JobNode(val job: Job) : CircleCITreeNode()
    class LoadingNode : CircleCITreeNode()
    class LoadMoreNode : CircleCITreeNode()
    class ErrorNode : CircleCITreeNode()
}
```

### 6. Coroutine Usage

**Patterns:**
- Use `CoroutineScope` with `SupervisorJob` in UI components
- Launch on `Dispatchers.Main` for UI updates
- Launch on `Dispatchers.IO` for API calls
- Cancel scope in `dispose()`

**Example:**
```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

scope.launch {
    projectService.detectProjects()
    treeModel.reloadRoot()
}
```

### 7. Icon System

**Location:** `com.circleci.idea.icons.CircleCIIcons`

**Pattern:**
```kotlin
object CircleCIIcons {
    val StatusSuccess = loadIcon("/icons/status-success.svg")
    val StatusFailed = loadIcon("/icons/status-failed.svg")
    val StatusRunning = loadIcon("/icons/status-running.svg")

    private fun loadIcon(path: String): Icon {
        return IconLoader.getIcon(path, CircleCIIcons::class.java)
    }
}
```

### 8. Logging

**Usage:**
```kotlin
private val logger = CircleCILogger.getInstance()

logger.info("Message")
logger.error("Error message", exception)
logger.logLifecycleEvent("Service initialized")
```

## Project Structure

```
src/main/kotlin/com/circleci/idea/
├── api/                          # API client and services
│   ├── CircleCIApiClient.kt      # Low-level HTTP client
│   ├── CircleCIApiService.kt     # Typed API endpoints
│   ├── ResponseCache.kt          # Caching layer
│   └── models/                   # API data models
├── toolwindow/                   # UI components
│   ├── CircleCIToolWindowFactory.kt
│   ├── CircleCIToolWindowContent.kt
│   ├── tree/                     # Tree view implementation
│   └── actions/                  # Toolbar actions
├── state/                        # State management
│   ├── CircleCIStateStore.kt     # Central state store
│   └── CircleCIState.kt          # State data classes
├── run/                          # Runs, workflows and jobs (V3 API)
│   ├── RunListService.kt         # Filtered run/workflow/job listing
│   ├── RunFilters.kt             # Scope, status and created filters
│   ├── RunMapper.kt              # V3 wire types → domain models
│   └── RunStatus.kt              # Status from phase/outcome
├── job/                          # Job pages (one editor tab per job)
│   ├── JobDetailsService.kt      # Opens job pages; fetches job, tests, artifacts
│   ├── JobEditorProvider.kt      # FileEditorProvider for JobVirtualFile
│   ├── JobPanel.kt               # Toolbar over the Compose page: header and tabs; polls the job
│   ├── JobPageUi.kt              # What the page's Compose views share (icons, Swing hosting)
│   ├── StepsTab.kt               # Steps tree and step output console
│   ├── StepOutputStream.kt       # Ranged stdout polling, as in the CLI
│   ├── TestsTab.kt               # Tests tab (TestFilter: client-side filtering and sorting)
│   ├── ArtifactsTab.kt           # Artifacts tab (ArtifactTree: the file tree)
│   └── ResourceUsageTab.kt       # Resource Usage tab (UsageChart: the line charts)
├── ssh/                          # SSH sessions into jobs, in Terminal tabs
│   ├── SshSessionService.kt      # Opens sessions (or explains why it can't)
│   └── IdeSshConnector.kt        # IDE SSH client + Terminal runner (optional deps)
├── auth/                         # Authentication
│   ├── CircleCIAuthService.kt
│   └── CircleCILoginDialog.kt
├── settings/                     # Settings UI
│   └── CircleCIConfigurable.kt
├── logging/                      # Logging infrastructure
│   └── CircleCILogger.kt
└── icons/                        # Icon system
    └── CircleCIIcons.kt
```

## Key Conventions

### Naming Conventions
- Services: `*Service` (e.g., `RunListService`)
- State: `*State` (e.g., `AuthState`)
- API Models: `*Wire` for V3 wire types (e.g., `RunWire`); `*Info`/`*Result` for what clients return (e.g., `UserInfo`)
- Domain Models: Plain names (e.g., `Run`, `Workflow`, `Job`)
- UI Components: `*Content`, `*Panel`, `*Dialog`

### File Organization
- One class per file
- Package by feature (e.g., `job/`, `run/`)
- API models in `api/models/`
- State classes in `state/`

### Error Handling
- Use `Result<T>` for API operations
- Log errors with `CircleCILogger`
- Display user-friendly error messages in UI
- Never expose raw exceptions to users

### Async Operations
- Always use coroutines for API calls
- Use `StateFlow` for reactive state
- Cancel coroutines in `dispose()`
- Handle errors gracefully

## Common Patterns

### Adding a New API Endpoint

1. Add a method to the domain's client in `api/clients/`, and expose it through `CircleCIApiService`:
```kotlin
fun getJobArtifacts(client: CircleCIApiClient, jobId: String): Result<List<ArtifactWire>> {
    return executeRequest(client, "/api/v3/jobs/$jobId/artifacts") { data ->
        gson.fromJson<V3List<ArtifactWire>>(data, object : TypeToken<V3List<ArtifactWire>>() {}.type).data.orEmpty()
    }
}
```

2. Create the wire model in `api/models/`, every field nullable (the V3 API omits what doesn't apply):
```kotlin
data class ArtifactWire(
    @SerializedName("attributes")
    val attributes: ArtifactAttributesWire? = null,
)
```

3. Test it against a local HTTP server, as `CircleCIApiServiceTest` does, rather than a mock

### Adding a New UI Panel

1. Create panel class extending `JBPanel`:
```kotlin
class MyPanel(private val project: Project) : JBPanel<MyPanel>(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        setupUI()
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

2. Register as tool window content:
```kotlin
val content = ContentFactory.getInstance().createContent(
    MyPanel(project),
    "Tab Name",
    false
)
toolWindow.contentManager.addContent(content)
```

### Adding State

1. Add state data class in `CircleCIState.kt`:
```kotlin
data class MyFeatureState(
    val data: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
```

2. Add to `CircleCIState` interface:
```kotlin
interface CircleCIState {
    val myFeature: StateFlow<MyFeatureState>
}
```

3. Implement in `CircleCIStateStore`:
```kotlin
private val _myFeature = MutableStateFlow(MyFeatureState())
override val myFeature: StateFlow<MyFeatureState> = _myFeature.asStateFlow()

fun updateMyFeature(data: String) {
    _myFeature.update { it.copy(data = data) }
}
```

## Testing Considerations

- Services are project-scoped, use mock projects for testing
- State management uses StateFlow, easy to test reactively
- API clients are tested against a local HTTP server (`CircleCIApiServiceTest`), not mocks
- UI components implement Disposable for proper cleanup

## Performance Considerations

- Use pagination for large data sets (`PaginationHelper`)
- Cache responses with TTL (`ResponseCache`)
- Rate limit API calls (token bucket in `CircleCIApiClient`)
- Deduplicate in-flight requests
- Lazy load tree nodes on expansion
