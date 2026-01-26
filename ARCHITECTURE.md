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
- Services like `JobDataService`, `PipelineDataService`, `WorkflowDataService`
- Handle fetching data from API
- Convert API models to domain models
- Update state store
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
fun getJobs(workflowId: String): Result<PaginatedResponse<JobInfo>> {
    return executeRequest("/api/v2/workflow/$workflowId/job") { data ->
        gson.fromJson(data.toString(), object : TypeToken<PaginatedResponse<JobInfo>>() {}.type)
    }
}
```

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
  - North: Toolbar
  - Center: Main content (tree, panel, etc.)

**Disposal:**
```kotlin
override fun dispose() {
    scope.cancel()
}
```

#### Tree View Pattern

**Components:**
- `CircleCITreeModel` - Manages tree structure, async loading
- `CircleCITreeNode` - Sealed class for node types
- `CircleCITreeCellRenderer` - Custom rendering with icons

**Async Loading:**
```kotlin
tree.addTreeExpansionListener(object : TreeExpansionListener {
    override fun treeExpanded(event: TreeExpansionEvent) {
        val node = event.path.lastPathComponent as? CircleCITreeNode ?: return
        if (node.canLoadChildren() && !node.childrenLoaded) {
            treeModel.loadChildren(node)  // Async
        }
    }
})
```

**Node Types:**
```kotlin
sealed class CircleCITreeNode {
    class RootNode : CircleCITreeNode()
    class ProjectNode(val project: CircleCIProject) : CircleCITreeNode()
    class PipelineNode(val pipeline: PipelineInfo) : CircleCITreeNode()
    class WorkflowNode(val workflow: WorkflowInfo) : CircleCITreeNode()
    class JobNode(val job: JobInfo) : CircleCITreeNode()
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
├── job/                          # Job-related services
│   └── JobDataService.kt
├── pipeline/                     # Pipeline services
│   └── PipelineDataService.kt
├── workflow/                     # Workflow services
│   └── WorkflowDataService.kt
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
- Services: `*Service` (e.g., `JobDataService`)
- State: `*State` (e.g., `AuthState`)
- API Models: `*Info` (e.g., `JobInfo`, `PipelineInfo`)
- Domain Models: Plain names (e.g., `Job`, `Pipeline`)
- UI Components: `*Content`, `*Panel`, `*Dialog`

### File Organization
- One class per file
- Package by feature (e.g., `job/`, `pipeline/`, `workflow/`)
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

1. Add method to `CircleCIApiService`:
```kotlin
fun getJobDetails(projectSlug: String, jobNumber: Long): Result<JobDetailsInfo> {
    return executeRequest("/api/v2/project/$projectSlug/job/$jobNumber") { data ->
        gson.fromJson(data.toString(), JobDetailsInfo::class.java)
    }
}
```

2. Create API model in `api/models/ApiModels.kt`:
```kotlin
data class JobDetailsInfo(
    val id: String,
    val name: String,
    val status: String,
    // ... other fields
)
```

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
- API client supports mocking via dependency injection
- UI components implement Disposable for proper cleanup

## Performance Considerations

- Use pagination for large data sets (`PaginationHelper`)
- Cache responses with TTL (`ResponseCache`)
- Rate limit API calls (token bucket in `CircleCIApiClient`)
- Deduplicate in-flight requests
- Lazy load tree nodes on expansion
