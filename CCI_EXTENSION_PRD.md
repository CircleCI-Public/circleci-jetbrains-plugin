# CircleCI Code Editor Extension - Product Requirements Document

## Executive Summary

This document defines the requirements for building CircleCI extensions across multiple code editor platforms. Based on the successful VSCode extension implementation, this PRD provides an editor-agnostic blueprint for delivering consistent CircleCI integration experiences across IDEs including JetBrains IDEs (IntelliJ, WebStorm, PyCharm), Neovim, Sublime Text, and others.

**Target Audience:** Engineering teams building CircleCI integrations for code editors
**Version:** 1.0
**Last Updated:** 2026-01-24

---

## Table of Contents

1. [Product Overview](#product-overview)
2. [Core Features & Requirements](#core-features--requirements)
3. [Architecture Requirements](#architecture-requirements)
4. [API Integration Requirements](#api-integration-requirements)
5. [UI/UX Requirements](#ui-ux-requirements)
6. [Configuration & Settings Requirements](#configuration--settings-requirements)
7. [Security & Authentication Requirements](#security--authentication-requirements)
8. [Implementation Considerations](#implementation-considerations)
9. [Success Metrics](#success-metrics)
10. [Appendices](#appendices)

---

## Product Overview

### Purpose

Enable developers to monitor, manage, and debug CircleCI pipelines directly within their preferred code editor, eliminating context switching between development and CI/CD management tools.

### Key Value Propositions

1. **Real-time Pipeline Visibility** - Monitor pipeline status without leaving the editor
2. **Configuration Intelligence** - Validate and author CircleCI configs with intelligent assistance
3. **Rapid Debugging** - SSH directly into failed jobs from the IDE
4. **Local Testing** - Test config changes before committing (unversioned config)
5. **Workflow Control** - Approve, rerun, and cancel workflows/jobs from the editor

### Target Users

- **Backend/Frontend Developers** - Monitor build status, debug failures
- **DevOps Engineers** - Author and validate CircleCI configurations
- **QA Engineers** - Review test results and artifacts
- **Engineering Managers** - Track team pipeline health

---

## Core Features & Requirements

### 1. Pipeline Management & Monitoring

#### 1.1 Pipeline Visualization

**Priority:** P0 (Must Have)

**Requirements:**
- Display hierarchical view of Projects → Pipelines → Workflows → Jobs
- Show pipeline status with visual indicators (running, success, failed, canceled, on-hold)
- Support pagination for large pipeline lists (10 items per page minimum)
- Display pipeline metadata:
  - Pipeline number and ID
  - Trigger source (user, schedule, webhook)
  - Branch name and commit SHA
  - Triggered by (user name/avatar)
  - Created timestamp
  - Duration (for completed pipelines)

**Editor Capabilities Required:**
- Tree/hierarchical view component
- Async data loading
- Custom icons/badges
- Expandable/collapsible nodes

---

#### 1.2 Real-time Status Updates

**Priority:** P0 (Must Have)

**Requirements:**
- Subscribe to pipeline/workflow/job status changes via WebSocket (Pusher)
- Auto-refresh UI when status changes occur
- Update badge counts for unread status changes
- Fallback to polling if WebSocket unavailable (30-second intervals)
- Handle network reconnection gracefully with exponential backoff

**Technical Specifications:**
- WebSocket library: Pusher.js or compatible
- Event types: `workflow.completed`, `job.started`, `job.completed`
- Channel naming: `private-<project-id>`
- Authentication: Pusher key from `/api/private/pusher/config`

**Editor Capabilities Required:**
- WebSocket client support
- Background task execution
- Event-driven UI updates

---

#### 1.3 Pipeline Filtering

**Priority:** P1 (Should Have)

**Requirements:**
- **Branch Filters:**
  - Current branch only (sync with git HEAD)
  - All branches
  - Default branch only
  - Custom branch selection
- **Author Filters:**
  - All pipelines
  - My pipelines only (triggered by authenticated user)
- **Status Filters:**
  - Success, Failed, Running, Canceled, On-hold, Error, Not-run, Unauthorized
  - Multi-select support
  - Persist filter preferences per workspace
- **Project Selection:**
  - Multi-project support
  - Auto-detect projects from git remotes
  - Manual project addition via project slug

**Editor Capabilities Required:**
- Multi-select filter UI
- Git integration (read current branch)
- Workspace-scoped preferences storage

---

### 2. Workflow Management

#### 2.1 Workflow Actions

**Priority:** P0 (Must Have)

**Requirements:**

| Action | Condition | API Endpoint | Confirmation Required |
|--------|-----------|--------------|----------------------|
| **Rerun from Start** | Workflow completed/failed/canceled | POST `/api/v2/workflow/{id}/rerun` | Yes |
| **Rerun from Failed** | Workflow failed + created <3 months ago | POST `/api/v2/workflow/{id}/rerun` (from_failed=true) | Yes |
| **Cancel Workflow** | Status: running/failing/on_hold | POST `/api/v2/workflow/{id}/cancel` | Yes |
| **Approve Workflow** | Status: on_hold + <3 months old | POST `/api/v2/workflow/{id}/approve/{approvalRequestId}` | Yes |

**Error Handling:**
- Display friendly error messages for failed actions
- Log errors to extension output/console
- Retry on 429 (rate limit) with exponential backoff

**Editor Capabilities Required:**
- Context menu support
- Confirmation dialogs
- HTTP client with retry logic

---

#### 2.2 Workflow Status Indicators

**Priority:** P1 (Should Have)

**Requirements:**
- Visual status badges: Running, Success, Failed, Canceled, On-hold, Needs Approval
- Status descriptions:
  - "Needs Approval" for on-hold workflows <3 months
  - "Expired" for on-hold workflows >3 months
  - "Running" with elapsed time
- Color coding:
  - Green: Success
  - Red: Failed
  - Yellow: Running/On-hold
  - Gray: Canceled/Not-run

**Editor Capabilities Required:**
- Custom status icons
- Dynamic text labels
- Color theming support

---

### 3. Job Monitoring & Debugging

#### 3.1 Job Display

**Priority:** P0 (Must Have)

**Requirements:**
- Show jobs nested under workflows
- Display job metadata:
  - Job name and number
  - Job type (build/approval)
  - Status (running/success/failed/not-run/on-hold/canceled)
  - Duration
  - Started/completed timestamps
- Show job steps with individual status indicators
- Display resource class (Docker, Machine, macOS)
- Show parallelism information (e.g., "3/5" for parallel runs)

**Editor Capabilities Required:**
- Multi-level tree view
- Timestamp formatting
- Dynamic status updates

---

#### 3.2 Job Actions

**Priority:** P0 (Must Have)

**Requirements:**

| Action | Availability | Confirmation |
|--------|--------------|-------------|
| **Open Job Details** | All jobs | No |
| **Rerun Job** | Completed jobs | Yes |
| **Rerun with SSH** | Build jobs | Yes |
| **Cancel Job** | Running/queued jobs | Yes |
| **Approve Job** | Approval jobs with on-hold status | Yes |
| **Copy Job Number** | All jobs | No |
| **Open in Browser** | All jobs | No |

**Technical Specifications:**
- Job details shown in dedicated panel/webview
- SSH rerun creates job with `ssh_enabled: true` parameter
- Cancel job via POST `/api/v2/project/{slug}/job/{number}/cancel`

**Editor Capabilities Required:**
- Context menus on tree items
- Clipboard API
- External browser launch

---

#### 3.3 SSH Debugging

**Priority:** P1 (Should Have)

**Requirements:**
- **Rerun with SSH:** Create new job run with SSH enabled
- **SSH Connection Options:**
  1. **Terminal Integration:** Open SSH session in editor's integrated terminal
  2. **Remote Development:** Open remote editor session (like VSCode Remote-SSH)
- **Parallel Run Selection:** For parallelized jobs, allow user to select which parallel run (0-N) to SSH into
- **SSH Configuration:**
  - Configure GitHub SSH private key path
  - Configure Bitbucket SSH private key path
  - Auto-detect SSH key from `~/.ssh/id_rsa` or `~/.ssh/id_ed25519`
  - Support custom SSH key paths
- **Platform Support:**
  - Windows: Support cmd, PowerShell, bash (WSL)
  - macOS/Linux: Use default shell
- **SSH Command Format:**
  ```bash
  ssh -p {port} -i {private_key_path} {user}@{host}
  ```

**Limitations:**
- SSH not available for GitHub App or GitLab projects (display warning)
- SSH timeout after 10 minutes for security jobs

**Editor Capabilities Required:**
- Terminal API
- Remote development protocol (optional)
- File system access for SSH keys
- Process spawning

---

#### 3.4 Job Output & Logs

**Priority:** P1 (Should Have)

**Requirements:**
- Display job step output in scrollable view
- Stream live output for running jobs
- Color-coded output (ANSI support)
- Show step timing information
- Download full log option
- Search within logs
- Auto-scroll to bottom for running jobs (with toggle)

**API Endpoints:**
- GET `/api/private/output/raw/{projectSlug}/{jobNumber}/output/{taskIndex}/{stepIndex}`
- GET `/api/private/output/raw/{projectSlug}/{jobNumber}/error/{taskIndex}/{stepIndex}`

**Editor Capabilities Required:**
- Rich text display (ANSI colors)
- Streaming data rendering
- Search functionality
- Large text file handling

---

### 4. Configuration File Support

#### 4.1 YAML Language Server Integration

**Priority:** P0 (Must Have)

**Requirements:**
- Integrate CircleCI YAML Language Server via LSP (Language Server Protocol)
- Language Server Binary: `circleci-yaml-language-server` (download from GitHub releases)
- **Auto-update:** Check for new releases weekly, prompt user to update
- **Update Policies:**
  - Automatic: Auto-download and update
  - Prompt: Ask user before updating
  - Never: Manual updates only

**LSP Features Required:**
- **Diagnostics:** Real-time error/warning highlighting
- **Hover Documentation:** Show documentation for YAML keys on hover
- **Auto-completion:** Suggest valid keys, executors, orbs, parameters
- **Go-to-Definition:** Navigate to job/executor/command definitions
- **Go-to-References:** Find all usages of jobs/executors
- **Code Actions:** Quick fixes for common errors

**File Scope:**
- Apply to files matching: `.circleci/**/*.yml`, `.circleci/**/*.yaml`

**Editor Capabilities Required:**
- LSP client implementation
- Binary download/management
- YAML syntax highlighting

---

#### 4.2 Configuration Validation

**Priority:** P0 (Must Have)

**Requirements:**
- **Validate Config Command:** Check if `.circleci/config.yml` is syntactically valid
  - API: POST `/api/v2/compile-config-with-defaults`
  - Shows errors with line numbers
  - Display compiled/expanded YAML output
- **Validate Policy Command:** Check config against organization policies
  - API: POST to policy service
  - Requires organization ID and context
  - Shows hard failures (blocking) and soft failures (warnings)
  - Allows branch selection for validation context
- **Visual Indicators:**
  - Green checkmark icon when config is valid
  - Red X icon when config has errors
  - Display in editor gutter and file explorer
- **Error Display:**
  - Inline error squiggles in editor
  - Error list panel with clickable navigation
  - Error message with CircleCI documentation links

**Editor Capabilities Required:**
- Editor diagnostics API
- File status decorations
- Output panel for detailed errors
- HTTP POST with file upload

---

#### 4.3 Configuration Compilation

**Priority:** P2 (Nice to Have)

**Requirements:**
- **Compile Config Command:** View fully expanded YAML after orb expansion
- Shows result in new editor tab
- Displays pipeline parameter resolution
- Shows dynamic config expansion (setup workflows)
- Format output YAML for readability

**Technical Specifications:**
- API: POST `/api/v2/compile-config-with-defaults`
- Response: `output_yaml` field contains compiled config
- Handle large compiled configs (>1MB) efficiently

**Editor Capabilities Required:**
- Open new editor buffer/tab
- Syntax highlighting for YAML
- Read-only editor mode

---

### 5. Local Configuration Testing (Unversioned Config)

#### 5.1 Test Run Feature

**Priority:** P1 (Should Have)

**Requirements:**
- **Trigger Pipeline with Local Config:** Run pipeline using modified config without committing to VCS
- **Workflow Selection:** Choose which workflows to execute
- **Job Selection:** Select specific jobs within workflows
- **Branch Selection:** Test against any branch (local or remote)
- **Prerequisites Check:**
  - Organization opt-in enabled
  - Project opt-in enabled (check via `/api/v1.1/project/{slug}/settings`)
  - No dynamic configuration in project
  - User has write access to project
- **Safety Checks:**
  - Warn if running unversioned config on default/main branch
  - Show confirmation dialog with security warning
  - Display which secrets/contexts will be accessible
- **UI Flow:**
  1. Detect config changes vs. committed version
  2. Show "Test Run" action in config file or tree view
  3. Present workflow/job selector
  4. Select target branch
  5. Confirm and trigger pipeline
  6. Show triggered pipeline in pipeline list

**API Specifications:**
- Endpoint: POST `/api/v2/project/{projectSlug}/pipeline`
- Headers: `Circle-Token: {token}`
- Body:
  ```json
  {
    "branch": "string",
    "parameters": {},
    "config_yaml": "string (base64 encoded config)"
  }
  ```

**Error Handling:**
- Feature not enabled: Show setup instructions with link to docs
- Invalid config: Show validation errors before triggering
- Permission denied: Show error with required permissions

**Editor Capabilities Required:**
- Git diff detection
- Multi-select UI (workflows/jobs)
- Confirmation dialogs
- File encoding (base64)

---

#### 5.2 Configuration Code Lens

**Priority:** P2 (Nice to Have)

**Requirements:**
- Display inline "Run" action buttons above workflows in `.circleci/config.yml`
- Show "Run workflow" above each workflow definition
- Show "Run job" above each job definition (if test run enabled)
- Click action triggers test run flow with pre-selected workflow/job
- Update code lens based on test run feature availability

**Editor Capabilities Required:**
- Code lens API
- YAML AST parsing
- Inline action buttons

---

### 6. Test & Artifact Management

#### 6.1 Test Results Display

**Priority:** P2 (Nice to Have)

**Requirements:**
- Display test results nested under jobs
- Show test metadata:
  - Test name, class name, file path
  - Status (passed/failed/skipped)
  - Duration
  - Error message and stack trace (for failures)
- **Flaky Test Indicator:** Mark tests with `[FLAKY]` badge if detected
- Pagination for large test suites (30 tests per page)
- Filter tests by status (all/failed/passed/skipped/flaky)
- Click test to open source file at line number (if path is local)

**API Specifications:**
- GET `/api/v2/project/{projectSlug}/{jobNumber}/tests`
- Paginated response with `nextPageToken`

**Editor Capabilities Required:**
- Tree view with filtering
- File navigation API
- Badge/tag rendering

---

#### 6.2 Artifact Management

**Priority:** P2 (Nice to Have)

**Requirements:**
- List artifacts under jobs
- Display artifact metadata:
  - File name and path
  - File size
  - Node index (for parallel runs)
- **Actions:**
  - Download artifact to local machine
  - Open artifact URL in browser
  - Copy artifact URL to clipboard
- Show download progress for large artifacts
- Support artifact preview for common types (text, JSON, XML, images)

**API Specifications:**
- GET `/api/v2/project/{projectSlug}/{jobNumber}/artifacts`

**Editor Capabilities Required:**
- File download API
- Progress indicators
- File preview panels

---

### 7. Notifications & Status Bar

#### 7.1 Workflow Status Notifications

**Priority:** P1 (Should Have)

**Requirements:**
- **Trigger Conditions:** Notify when workflow status changes to monitored statuses
- **Default Monitored Statuses:** Failed, Failing, Canceled, Error, On-hold, Not-run, Unauthorized
- **Excluded by Default:** Success, Running (too noisy)
- **Notification Content:**
  - Workflow name
  - Status change (e.g., "has failed", "needs approval")
  - Pipeline number and branch
  - Triggered by user name
- **Action Buttons:**
  - "View" - Navigate to workflow in tree view
  - "View Logs" (for failures) - Open job details
  - "Approve" (for on-hold) - Approve workflow
  - "Silence" - Temporarily mute notifications
  - "Disable" - Turn off notifications completely
- **Filtering:**
  - "My Pipelines Only" - Only notify for user's own pipelines
  - Status filter - Configure which statuses trigger notifications
  - Mute all notifications toggle
- **Persistence:** Save notification preferences per workspace

**Editor Capabilities Required:**
- Toast/notification API
- Action buttons in notifications
- Notification preferences storage

---

#### 7.2 Status Bar Integration

**Priority:** P1 (Should Have)

**Requirements:**
- Display CircleCI icon with current project status
- Show status of most recent workflow:
  - Text: "CircleCI: {status}" (e.g., "CircleCI: ✓ Success")
  - Color: Green (success), Red (failed), Yellow (running/on-hold), Gray (not logged in)
- **Click Actions:**
  - Open pipelines panel/tree view
  - Quick navigation to latest workflow
- **States:**
  - Not logged in: "CircleCI: Log In"
  - Invalid token: "CircleCI: Token Invalid"
  - No projects: "CircleCI: No Projects"
  - No internet: "CircleCI: Offline"
  - Loading: "CircleCI: Loading..."
  - Normal: "CircleCI: {icon} {status}"

**Editor Capabilities Required:**
- Status bar item API
- Color/icon customization
- Click handlers

---

### 8. Project Management

#### 8.1 Project Auto-detection

**Priority:** P0 (Must Have)

**Requirements:**
- **Auto-detect Projects:** Scan workspace folders for git repositories
- **Git Remote Parsing:** Extract VCS info from git remote URLs
  - Supported formats: GitHub, Bitbucket, GitLab
  - Parse organization, project name, VCS host
- **Project Registration:** Automatically register detected projects
- **Multi-root Workspace Support:** Detect projects in all workspace folders
- **Refresh on Git Changes:** Re-scan when `.git/config` changes

**Algorithm:**
1. Find all git repositories in workspace
2. Parse remote URLs (typically `origin`)
3. Match against followed projects via CircleCI API
4. Create local project mappings
5. Subscribe to pipeline updates for detected projects

**Editor Capabilities Required:**
- Workspace folder enumeration
- Git integration or command execution
- File system watchers

---

#### 8.2 Project Selection

**Priority:** P0 (Must Have)

**Requirements:**
- **Multi-project Support:** Follow and display multiple CircleCI projects
- **Project Picker UI:** Show all followed projects with search/filter
- **Project Sources:**
  - Auto-detected from workspace
  - Followed projects from CircleCI account
  - Manually added by project slug
- **Persistence:** Save selected projects per workspace
- **Project Metadata Display:**
  - Organization name and slug
  - Project name
  - VCS provider (GitHub/Bitbucket/GitLab)
  - Default branch
  - Followed status

**API Specifications:**
- GET `/api/v1.1/projects` - List followed projects
- GET `/api/private/me/followed-projects` - Paginated followed projects
- POST `/api/v1.1/project/{projectSlug}/follow` - Follow project

**Editor Capabilities Required:**
- Multi-select picker UI
- Async search/filter
- Workspace-scoped storage

---

### 9. Help & Feedback

#### 9.1 Documentation Access

**Priority:** P2 (Nice to Have)

**Requirements:**
- **Help Panel:** Dedicated view with links to:
  - CircleCI Documentation
  - Extension documentation
  - Release notes / What's new
  - Community forum (CircleCI Discuss)
  - Feature request portal
  - GitHub issues (for bug reports)
- **Contextual Help:**
  - Inline documentation in YAML hovers (via LSP)
  - Links to CircleCI docs from error messages
  - Quick access to specific doc pages from errors

**Editor Capabilities Required:**
- Custom panel/view
- External link handling
- Web view (optional)

---

#### 9.2 Feedback & Telemetry

**Priority:** P1 (Should Have)

**Requirements:**
- **Send Feedback Command:** Open email client or web form
- **Telemetry Events:** Track anonymous usage metrics (with user consent):
  - Extension activation/deactivation
  - Feature usage (commands executed)
  - Error rates
  - Performance metrics (API latency)
- **Privacy:**
  - Opt-in telemetry collection
  - No PII (personally identifiable information)
  - No code or config content
  - Clear privacy policy
- **Error Reporting:** Crash/error reporting to service (e.g., Rollbar/Sentry)

**Editor Capabilities Required:**
- Email/URL launcher
- Analytics SDK integration
- User consent management

---

## Architecture Requirements

### 1. State Management

**Priority:** P0 (Must Have)

**Requirements:**
- **Centralized State Store:** Single source of truth for extension state
- **Reactive Updates:** UI components subscribe to state changes
- **State Slices:**
  - `auth` - Authentication token and host URL
  - `projects` - Selected projects and metadata
  - `projectsData` - Pipeline, workflow, job data per project
  - `config` - Parsed CircleCI config for workspace
  - `workflowFilters` - Branch, author, status filters
  - `notificationFilters` - Notification preferences
  - `ui` - UI state (expanded items, selected items)
- **Persistence:** Serialize relevant state to workspace/global storage
- **State Hydration:** Load persisted state on extension activation

**Recommended Patterns:**
- Redux/MobX for JavaScript/TypeScript
- State management libraries native to editor platform
- Observable pattern for reactive updates

---

### 2. API Client Architecture

**Priority:** P0 (Must Have)

**Requirements:**

#### 2.1 HTTP Client Configuration
- **Base URL:** Configurable (default: `https://circleci.com`)
- **Headers:**
  - `Circle-Token: {userToken}` - Authentication
  - `User-Agent: {EditorName}-Extension/{version}`
  - `Cache-Control: private, max-age=3600`
- **Timeouts:** 30 seconds default, 120 seconds for long operations
- **Retry Logic:**
  - Retry on 429 (rate limit) only
  - Maximum 3 retries
  - Exponential backoff: 1s, 2s, 4s
- **Error Handling:**
  - Parse CircleCI error responses
  - Detect invalid token (401)
  - Detect rate limiting (429)
  - Detect network errors (DNS, timeout)

#### 2.2 Request Deduplication
- Cache identical in-flight GET/HEAD requests
- Key: `{method} {url}`
- Return same promise for duplicate requests
- Clear cache on response completion

#### 2.3 Rate Limiting
- Client-side rate limiter: 50 requests per second maximum
- Queue requests when limit exceeded
- Priority queue for user-initiated actions

#### 2.4 Response Transformation
- Convert API snake_case to camelCase
- Parse ISO8601 timestamps to native date objects
- Normalize error response formats

---

### 3. Pagination Strategy

**Priority:** P0 (Must Have)

**Requirements:**
- **Cursor-based Pagination:** Use `nextPageToken` for pagination
- **Page Size:** 10 items default (configurable)
- **Auto-pagination Helpers:**
  - `getAllPages()` - Fetch all pages recursively
  - `getMultiplPages(depth)` - Fetch N pages
- **UI Pagination:**
  - "Load More" items in tree views
  - Infinite scroll for web panels
  - Page indicators showing "X of Y"
- **Caching:** Cache paginated responses per page token

**API Pattern:**
```
GET /api/v2/resource?page-token={token}
Response: {
  items: [...],
  next_page_token: "abc123"
}
```

---

### 4. WebSocket Integration

**Priority:** P1 (Should Have)

**Requirements:**
- **Provider:** Pusher WebSocket service
- **Configuration:**
  - Fetch Pusher key from GET `/api/private/pusher/config`
  - Connect to Pusher WebSocket endpoint
  - Subscribe to project-specific channels: `private-{projectId}`
- **Event Types:**
  - `workflow.completed` - Workflow finished
  - `job.started` - Job started
  - `job.completed` - Job finished
- **Connection Management:**
  - Auto-reconnect on disconnect
  - Exponential backoff: 1s, 2s, 4s, 8s, max 30s
  - Fallback to polling if WebSocket fails 3 times
- **Message Handling:**
  - Parse JSON event payload
  - Update state store with new data
  - Trigger UI refresh
  - Show notifications based on filters

---

### 5. Logging & Debugging

**Priority:** P1 (Should Have)

**Requirements:**
- **Log Levels:** DEBUG, INFO, WARN, ERROR
- **Log Outputs:**
  - Extension output channel (visible to user)
  - Log file (for debugging)
  - Console (development only)
- **Logged Events:**
  - Extension activation/deactivation
  - API requests (method, URL, status code)
  - API errors (full response body)
  - WebSocket connections/disconnections
  - State changes (at DEBUG level)
  - User actions (commands executed)
- **Log Rotation:** Rotate log files at 10MB, keep last 5 files
- **Privacy:** Redact tokens and sensitive data in logs

---

### 6. Error Tracking

**Priority:** P2 (Nice to Have)

**Requirements:**
- Integrate error tracking service (Rollbar, Sentry, etc.)
- Capture unhandled exceptions
- Capture API errors (non-2xx responses)
- Include context:
  - Extension version
  - Editor version
  - Platform (OS, architecture)
  - User ID (anonymized)
- User opt-in required
- Source map support for stack traces

---

## API Integration Requirements

### 1. CircleCI API Endpoints

**Base URLs:**
- **Cloud:** `https://circleci.com`
- **Server:** User-configurable (e.g., `https://circleci.company.com`)

**API Versions Used:**
- **v2 (Primary):** Modern REST API, preferred for all operations
- **v1.1 (Legacy):** Used for detailed job info and some project operations
- **Private API:** Used for job output, Pusher config (subject to change)
- **GraphQL:** Deprecated fallback for config validation on older servers

---

### 2. Authentication

**Endpoint:** `GET /api/v2/me`

**Purpose:** Validate token and get user information

**Request:**
```
GET /api/v2/me
Headers:
  Circle-Token: {userToken}
```

**Response:**
```json
{
  "id": "uuid",
  "login": "username",
  "name": "User Name"
}
```

**Error Handling:**
- 401 Unauthorized: Token is invalid
- 404 Not Found: Token has no access to API

---

### 3. Pipeline Operations

#### 3.1 Get Pipelines

**Endpoints:**
- `GET /api/v2/project/{projectSlug}/pipeline/mine` - User's pipelines
- `GET /api/v2/project/{projectSlug}/pipeline` - All pipelines
- `GET /api/v2/pipeline?org-slug={orgSlug}` - Organization pipelines

**Query Parameters:**
- `branch` - Filter by branch name
- `page-token` - Pagination cursor

**Response Structure:**
```json
{
  "items": [
    {
      "id": "uuid",
      "number": 123,
      "project_slug": "gh/org/repo",
      "state": "created",
      "created_at": "ISO8601",
      "trigger": {
        "type": "webhook",
        "received_at": "ISO8601",
        "actor": {
          "login": "username",
          "avatar_url": "https://..."
        }
      },
      "vcs": {
        "branch": "main",
        "commit": {
          "subject": "commit message",
          "body": "commit body"
        },
        "origin_repository_url": "https://...",
        "provider_name": "GitHub",
        "revision": "sha",
        "tag": "v1.0.0",
        "target_repository_url": "https://..."
      },
      "errors": []
    }
  ],
  "next_page_token": "string"
}
```

#### 3.2 Get Pipeline Config

**Endpoint:** `GET /api/v2/pipeline/{pipelineId}/config`

**Response:**
```json
{
  "source": "string (YAML config)",
  "compiled": "string (expanded YAML)"
}
```

---

### 4. Workflow Operations

#### 4.1 Get Workflows

**Endpoint:** `GET /api/v2/pipeline/{pipelineId}/workflow`

**Response:**
```json
{
  "items": [
    {
      "id": "uuid",
      "name": "build-and-test",
      "project_slug": "gh/org/repo",
      "pipeline_id": "uuid",
      "pipeline_number": 123,
      "status": "success",
      "started_by": "uuid",
      "created_at": "ISO8601",
      "stopped_at": "ISO8601"
    }
  ],
  "next_page_token": "string"
}
```

**Statuses:**
- `running` - In progress
- `success` - All jobs succeeded
- `failed` - At least one job failed
- `failing` - Jobs currently failing
- `canceled` - Manually canceled
- `on_hold` - Waiting for approval
- `not_run` - Not executed
- `error` - System error
- `unauthorized` - Permission denied

#### 4.2 Rerun Workflow

**Endpoint:** `POST /api/v2/workflow/{workflowId}/rerun`

**Request Body:**
```json
{
  "from_failed": false,
  "enable_ssh": false,
  "sparse_tree": false,
  "jobs": ["job-id-1", "job-id-2"]
}
```

**Parameters:**
- `from_failed` - Only rerun failed jobs (default: false)
- `enable_ssh` - Enable SSH for all jobs (default: false)
- `sparse_tree` - Only rerun specified jobs (default: false)
- `jobs` - Array of job IDs to rerun (requires sparse_tree=true)

#### 4.3 Cancel Workflow

**Endpoint:** `POST /api/v2/workflow/{workflowId}/cancel`

#### 4.4 Approve Workflow

**Endpoint:** `POST /api/v2/workflow/{workflowId}/approve/{approvalRequestId}`

---

### 5. Job Operations

#### 5.1 Get Jobs

**Endpoint:** `GET /api/v2/workflow/{workflowId}/job`

**Response:**
```json
{
  "items": [
    {
      "id": "uuid",
      "job_number": 123,
      "name": "build",
      "project_slug": "gh/org/repo",
      "status": "success",
      "type": "build",
      "started_at": "ISO8601",
      "stopped_at": "ISO8601",
      "dependencies": ["job-id"],
      "approved_by": "uuid"
    }
  ],
  "next_page_token": "string"
}
```

**Job Statuses:**
- `running`, `success`, `failed`, `canceled`, `blocked`, `on_hold`, `not_run`, `retried`, `infrastructure_fail`, `timedout`, `queued`, `not_running`, `unauthorized`

#### 5.2 Get Job Details (v1.1)

**Endpoint:** `GET /api/v1.1/project/{projectSlug}/{jobNumber}`

**Response:** Contains detailed job information including:
- Steps with output
- SSH users for SSH-enabled jobs
- Parallelism information
- Resource class
- Executor type

#### 5.3 Cancel Job

**Endpoint:** `POST /api/v2/project/{projectSlug}/job/{jobNumber}/cancel`

#### 5.4 Get Job Tests

**Endpoint:** `GET /api/v2/project/{projectSlug}/{jobNumber}/tests`

**Response:**
```json
{
  "items": [
    {
      "name": "test name",
      "classname": "TestClass",
      "file": "path/to/test.py",
      "result": "failure",
      "message": "error message",
      "source": "source code snippet",
      "run_time": 1.23
    }
  ],
  "next_page_token": "string"
}
```

#### 5.5 Get Job Artifacts

**Endpoint:** `GET /api/v2/project/{projectSlug}/{jobNumber}/artifacts`

**Response:**
```json
{
  "items": [
    {
      "path": "path/to/artifact.json",
      "node_index": 0,
      "url": "https://..."
    }
  ],
  "next_page_token": "string"
}
```

---

### 6. Configuration Validation

#### 6.1 Validate Config

**Endpoint:** `POST /api/v2/compile-config-with-defaults`

**Request:**
```json
{
  "config_yaml": "string (YAML content)",
  "pipeline_values": {
    "branch": "main",
    "project_slug": "gh/org/repo"
  }
}
```

**Response (Valid):**
```json
{
  "valid": true,
  "source_yaml": "string",
  "output_yaml": "string (compiled YAML)",
  "errors": []
}
```

**Response (Invalid):**
```json
{
  "valid": false,
  "errors": [
    {
      "type": "config",
      "message": "error description"
    }
  ]
}
```

#### 6.2 Validate Policy (Enterprise)

**Endpoint:** `POST https://internal.circleci.com/api/v1/owner/{orgId}/context/{context}/decision`

**Requirements:** CircleCI Server only, not available on Cloud

---

### 7. Project Operations

#### 7.1 Get Followed Projects

**Endpoint:** `GET /api/v1.1/projects`

**Response:**
```json
[
  {
    "reponame": "repo-name",
    "username": "org-name",
    "vcs_url": "https://github.com/org/repo",
    "vcs_type": "github",
    "followed": true,
    "default_branch": "main"
  }
]
```

#### 7.2 Get Project Settings

**Endpoint:** `GET /api/v1.1/project/{projectSlug}/settings`

**Response:** Contains project settings including:
- `runDryRunFromVSCode` - Test run feature enabled
- Security settings
- Build settings

---

### 8. Trigger Pipeline (Test Run)

**Endpoint:** `POST /api/v2/project/{projectSlug}/pipeline`

**Request:**
```json
{
  "branch": "main",
  "parameters": {},
  "config_yaml": "base64-encoded-config"
}
```

**Response:**
```json
{
  "id": "uuid",
  "number": 123,
  "state": "pending",
  "created_at": "ISO8601"
}
```

---

### 9. WebSocket Configuration

**Endpoint:** `GET /api/private/pusher/config`

**Response:**
```json
{
  "key": "pusher-key",
  "ws_endpoint": "wss://..."
}
```

---

### 10. Rate Limiting

**Limits:**
- Cloud: ~1000 requests per minute per user
- Server: Configurable per installation

**Rate Limit Headers:**
```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 999
X-RateLimit-Reset: 1609459200
```

**Rate Limit Response (429):**
```json
{
  "message": "Too Many Requests"
}
```

**Handling:** Retry after delay specified in `Retry-After` header or use exponential backoff

---

## UI/UX Requirements

### 1. Core UI Components

**Required UI Components by Editor:**

| Component | Purpose | Examples |
|-----------|---------|----------|
| **Tree View** | Hierarchical project/pipeline/workflow/job display | VSCode TreeView, JetBrains Tree |
| **Status Bar** | Global status indicator | Bottom bar item with icon |
| **Notification/Toast** | Alert user of status changes | System notifications or in-editor toasts |
| **Panel/Tool Window** | Detailed job output and logs | Bottom panel or side tool window |
| **Quick Pick/Picker** | Multi-select menus | Command palette-style picker |
| **Web Panel** | Rich HTML UI for auth, settings | Embedded browser view |
| **Context Menu** | Right-click actions | Tree item actions |
| **Icon/Badge** | Visual status indicators | Status icons, unread badges |

---

### 2. Visual Design System

#### 2.1 Status Colors

| Status | Color (Light) | Color (Dark) | Icon |
|--------|--------------|-------------|------|
| Success | Green (#28a745) | Green (#3fb950) | ✓ Checkmark |
| Failed | Red (#dc3545) | Red (#f85149) | ✗ X-mark |
| Running | Yellow (#ffc107) | Yellow (#d29922) | ⟳ Spinner |
| Canceled | Gray (#6c757d) | Gray (#8b949e) | ⊘ Circle-slash |
| On-hold | Blue (#007bff) | Blue (#58a6ff) | ⏸ Pause |
| Not Run | Gray (#6c757d) | Gray (#8b949e) | ○ Circle-outline |

#### 2.2 Icon System

**Status Icons** (SVG format recommended):
- `circleci-logo.svg` - Main extension icon
- `pipeline-success.svg`, `pipeline-failed.svg`, `pipeline-running.svg`
- `job-success.svg`, `job-failed.svg`, `job-running.svg`, `job-canceled.svg`
- `workflow-success.svg`, `workflow-failed.svg`, etc.

**Action Icons:**
- `rerun.svg`, `cancel.svg`, `approve.svg`, `ssh.svg`, `open-browser.svg`, `copy.svg`

**Theme Support:**
- Provide both light and dark theme variants
- Use editor's native theme colors where possible
- Use semantic color names (e.g., `editor.foreground`, `statusBarItem.errorBackground`)

---

### 3. Tree View Structure

**Hierarchy:**
```
📦 Projects (Root)
├─ 📁 org/repo-1 (Project)
│  ├─ 📋 #123 main ✓ (Pipeline - Success)
│  │  ├─ ⚙️ build-and-test ✓ (Workflow - Success)
│  │  │  ├─ 🔧 build ✓ (Job - Success)
│  │  │  ├─ 🔧 test ✓ (Job - Success)
│  │  │  └─ 🔧 deploy ⏸ (Job - On-hold)
│  │  └─ ⚙️ lint ✓ (Workflow - Success)
│  │     └─ 🔧 eslint ✓ (Job - Success)
│  ├─ 📋 #122 feature-branch ✗ (Pipeline - Failed)
│  │  └─ ⚙️ build-and-test ✗ (Workflow - Failed)
│  │     ├─ 🔧 build ✓ (Job - Success)
│  │     └─ 🔧 test ✗ (Job - Failed)
│  └─ ⏳ Load More (Pagination)
└─ 📁 org/repo-2 (Project)
   └─ 📋 #45 main ⟳ (Pipeline - Running)
```

**Tree Item Properties:**
- **Label:** Primary text (job name, pipeline number, etc.)
- **Description:** Secondary text (branch name, duration, elapsed time)
- **Icon:** Status icon (colored SVG)
- **Tooltip:** Hover text with detailed info
- **Collapsible State:** Expanded/collapsed
- **Context Value:** For context menu filtering
- **Command:** On-click action (optional)

**Interaction Patterns:**
- Click: Expand/collapse or execute command
- Right-click: Show context menu with actions
- Hover: Show tooltip with metadata
- Badge: Unread count on parent items

---

### 4. Context Menus

**Job Context Menu:**
```
Open Job Details
Copy Job Number
──────────────────
Rerun Job
Rerun with SSH
──────────────────
Cancel Job (if running)
──────────────────
Open in Browser
```

**Workflow Context Menu:**
```
Rerun from Start
Rerun from Failed (if applicable)
──────────────────
Cancel Workflow (if running)
──────────────────
Open in Browser
```

**Pipeline Context Menu:**
```
Open Pipeline YAML
Show Config Errors (if errors exist)
──────────────────
Open in Browser
Copy Pipeline Number
```

---

### 5. Status Bar

**Content:** `$(circleci-icon) {status-text}`

**States:**
- Not logged in: `$(circleci-icon) Log In` (gray, click to open login)
- Invalid token: `$(circleci-icon) Token Invalid` (red)
- Loading: `$(circleci-icon) Loading...` (yellow)
- No pipelines: `$(circleci-icon) No Pipelines` (gray)
- Latest status: `$(circleci-icon) ✓ build-and-test` (green)
- Failed: `$(circleci-icon) ✗ build-and-test` (red)
- Running: `$(circleci-icon) ⟳ build-and-test` (yellow)

**Click Action:** Open pipelines panel/tree view

---

### 6. Notifications

**Structure:**
```
┌──────────────────────────────────────┐
│ ⚠️ Workflow "build-and-test" failed   │
│ Pipeline #123 on branch main         │
│                                      │
│ [View Logs] [Rerun] [Dismiss]       │
└──────────────────────────────────────┘
```

**Action Buttons:**
- **View:** Navigate to workflow in tree view
- **View Logs:** Open job details panel (for failures)
- **Approve:** Approve workflow (for on-hold)
- **Rerun:** Rerun workflow
- **Dismiss:** Close notification
- **Silence:** Mute future notifications

**Notification Frequency:**
- Throttle: Maximum 1 notification per workflow every 5 minutes
- Deduplicate: Don't show duplicate notifications for same workflow status

---

### 7. Webview Panels

#### 7.1 Authentication Webview

**Purpose:** OAuth login flow and token input

**Content:**
- CircleCI logo and branding
- "Log in to CircleCI" heading
- **Option 1:** OAuth button (opens CircleCI OAuth page in browser)
- **Option 2:** Personal token input field + "Save Token" button
- Link to token creation page: `https://app.circleci.com/settings/user/tokens`
- Link to documentation

**Size:** 600x400px minimum

#### 7.2 Job Details Webview

**Purpose:** Display job steps, output, and metadata

**Sections:**
- **Header:**
  - Job name and number
  - Status badge
  - Duration
  - Resource class (executor)
  - SSH information (if enabled)
- **Steps List:**
  - Step name
  - Duration
  - Status icon
  - Expand/collapse for output
- **Output Panel:**
  - Color-coded terminal output (ANSI support)
  - Timestamps
  - Scrollable with auto-scroll toggle
  - Search within output
- **Actions:**
  - Rerun Job
  - Rerun with SSH
  - Cancel Job
  - Copy SSH command

**Size:** Full panel width, 600px height minimum

#### 7.3 Settings Webview

**Purpose:** Configure extension preferences

**Sections:**
- **Authentication:**
  - Host URL (for CircleCI Server)
  - Token management (change, revoke)
  - Login/logout buttons
- **Filters:**
  - Branch filter (current/all/default)
  - My pipelines only toggle
  - Status filter checkboxes
- **Notifications:**
  - Enable/disable notifications
  - Status filter for notifications
  - My pipelines only for notifications
- **SSH Configuration:**
  - GitHub SSH key path
  - Bitbucket SSH key path
  - Terminal vs. remote window preference
  - Windows shell selection
- **Advanced:**
  - Language server update policy
  - Log level
  - Telemetry opt-in/out

**Size:** Full panel width, scrollable

---

### 8. Loading States

**Tree View Loading:**
- Show spinner icon with "Loading..." text
- Disable actions during loading
- Show skeleton/placeholder items (optional)

**Panel Loading:**
- Show centered spinner with "Loading job details..." message
- 10-second timeout with error message

**Infinite Loading:**
- Show "Load More" button/item at end of list
- Click to load next page
- Replace with spinner during loading

---

### 9. Empty States

**No Projects:**
```
┌────────────────────────────────────┐
│          $(circleci-icon)          │
│                                    │
│      No CircleCI projects found    │
│                                    │
│  Projects are auto-detected from   │
│  your workspace's git repositories.│
│                                    │
│  [Select Projects Manually]        │
└────────────────────────────────────┘
```

**No Pipelines:**
```
No pipelines found for this project.
Try running a pipeline in CircleCI to see it here.
```

**Not Logged In:**
```
Log in to CircleCI to view your pipelines.
[Log In]
```

---

### 10. Error States

**API Error:**
```
⚠️ Failed to load pipelines
Error: Request timeout

[Retry] [View Logs]
```

**Invalid Config:**
```
❌ Configuration Error (line 42)
Unknown key "machin" (did you mean "machine"?)

[View in CircleCI Docs]
```

**Permission Error:**
```
⚠️ Permission Denied
You don't have access to this project.

[Check Permissions]
```

---

## Configuration & Settings Requirements

### 1. Settings Schema

**Required Settings:**

```typescript
{
  // Authentication
  "circleci.apiToken": string | null,          // CircleCI API token (sensitive)
  "circleci.hostUrl": string | null,           // CircleCI instance URL (default: https://circleci.com)

  // Project Selection
  "circleci.projects": string[],               // Selected project slugs (e.g., ["gh/org/repo"])

  // Filters
  "circleci.filters.branch": "current" | "all" | "default" | string,  // Branch filter
  "circleci.filters.myPipelinesOnly": boolean, // Show only user's pipelines
  "circleci.filters.status": string[],         // Status filter (e.g., ["success", "failed"])

  // Notifications
  "circleci.notifications.enabled": boolean,   // Master enable/disable
  "circleci.notifications.myPipelinesOnly": boolean,  // Only notify for user's pipelines
  "circleci.notifications.status": string[],   // Statuses to notify (default: all except success/running)

  // SSH Configuration
  "circleci.ssh.mode": "terminal" | "remote",  // How to open SSH sessions
  "circleci.ssh.githubKeyPath": string,        // Path to GitHub SSH key
  "circleci.ssh.bitbucketKeyPath": string,     // Path to Bitbucket SSH key
  "circleci.ssh.windowsShell": "default" | "bash" | "cmd" | "powershell",

  // Language Server
  "circleci.languageServer.updatePolicy": "automatic" | "prompt" | "never",

  // Advanced
  "circleci.telemetry.enabled": boolean,       // Opt-in telemetry
  "circleci.logging.level": "debug" | "info" | "warn" | "error",
  "circleci.theme": "default" | "light" | "dark"
}
```

---

### 2. Settings Storage

**Storage Levels:**
- **User/Global:** Settings that apply across all workspaces (auth token, host URL, SSH keys)
- **Workspace:** Settings specific to workspace (selected projects, filters)
- **Folder:** Settings for individual folders in multi-root workspaces

**Sensitive Data:**
- Store API token in secure credential store (e.g., OS keychain, secret storage API)
- Never store token in plaintext configuration files
- Encrypt token in settings JSON if secure storage unavailable

---

### 3. Settings UI

**Requirements:**
- Native settings integration (use editor's settings UI framework)
- Settings search/filter support
- Input validation (e.g., URL format for hostUrl)
- Descriptions and examples for each setting
- Links to documentation for complex settings
- Reset to defaults option

**Alternative:** Custom settings webview with form-based UI

---

### 4. Configuration Validation

**Validation Rules:**
- `apiToken`: Must be non-empty if set
- `hostUrl`: Must be valid HTTP/HTTPS URL
- `projects`: Must match format `{vcs-type}/{org}/{repo}` (e.g., `gh/circleci/circleci`)
- SSH key paths: Must be valid file paths (warn if file doesn't exist)

---

### 5. Configuration Migration

**Requirements:**
- Detect old/deprecated settings on extension update
- Migrate to new setting names automatically
- Show notification informing user of migration
- Provide rollback option if migration causes issues

---

## Security & Authentication Requirements

### 1. Token Storage

**Priority:** P0 (Must Have)

**Requirements:**
- **Secure Storage:** Use editor's secure credential storage API
  - macOS: Keychain
  - Windows: Credential Manager
  - Linux: Secret Service API (libsecret)
- **Fallback:** If secure storage unavailable, encrypt token before storing in config
- **Never Plaintext:** Never store token in plaintext in config files or logs
- **Token Validation:** Validate token on input by calling `/api/v2/me`
- **Token Expiry Detection:** Detect 401 errors and prompt re-authentication

---

### 2. Token Permissions

**Required Scopes:**
- Read pipelines, workflows, jobs
- Read project information
- Write: Trigger pipelines, cancel jobs/workflows, approve jobs

**CircleCI Token Types:**
- **Personal API Token:** User-created, full API access
- **Project API Token:** Limited to single project (not recommended for extension)

**Documentation:** Link to token creation: `https://app.circleci.com/settings/user/tokens`

---

### 3. OAuth Flow (Optional)

**Priority:** P2 (Nice to Have)

**Requirements:**
- OAuth 2.0 authorization code flow
- Redirect URI: `http://localhost:{port}/callback` or custom protocol handler
- Open browser to CircleCI OAuth consent page
- Capture authorization code from callback
- Exchange code for access token
- Store token securely

**Endpoints:**
- Authorize: `https://circleci.com/oauth/authorize?client_id={id}&redirect_uri={uri}`
- Token: `POST https://circleci.com/oauth/token`

**Challenges:**
- Not all editors support OAuth flows
- Requires registered OAuth app with CircleCI
- Personal tokens are simpler and sufficient for most use cases

---

### 4. Sensitive Data Handling

**Requirements:**
- **Redact Tokens:** Never log or display tokens in plaintext
  - Show only: `token: **********` or last 4 characters `token: ***abc123`
- **Redact in Errors:** Redact tokens from error messages and stack traces
- **Secure Transmission:** Always use HTTPS for API requests
- **No Token Sharing:** Never send token to third-party services
- **Clear on Logout:** Securely delete token from storage on logout

---

### 5. Host URL Validation

**Requirements:**
- Validate host URL is reachable before saving
- Call `GET {hostUrl}/api/v2/me` without credentials to test
- Warn if host doesn't respond or returns error
- Support self-signed certificates for CircleCI Server (with user confirmation)

---

### 6. Security Best Practices

- **Principle of Least Privilege:** Request only necessary API permissions
- **No Hardcoded Secrets:** No API tokens or keys in extension code
- **Regular Security Audits:** Scan dependencies for vulnerabilities
- **Secure Communication:** Use TLS 1.2+ for all API requests
- **Input Sanitization:** Sanitize all user inputs before displaying
- **XSS Prevention:** Escape HTML in webviews to prevent XSS attacks

---

## Implementation Considerations

### 1. Editor-Specific Adaptations

#### Visual Studio Code
- **API:** VSCode Extension API
- **Language:** TypeScript
- **Tree View:** TreeDataProvider interface
- **Webviews:** Webview API with HTML/CSS/JS
- **Storage:** ExtensionContext.secrets + globalState/workspaceState
- **LSP:** vscode-languageclient package
- **Notifications:** window.showInformationMessage

#### JetBrains IDEs (IntelliJ, WebStorm, PyCharm)
- **API:** IntelliJ Platform SDK
- **Language:** Kotlin or Java
- **Tree View:** ToolWindow with SimpleTree or JTree
- **UI:** Swing or Kotlin UI DSL
- **Storage:** PropertiesComponent + PasswordSafe
- **LSP:** LSP Support plugin or custom implementation
- **Notifications:** Notifications.Bus

#### Neovim
- **API:** Lua API (nvim-lua)
- **Language:** Lua
- **Tree View:** nvim-tree or custom floating window
- **UI:** Telescope.nvim for pickers, built-in floating windows
- **Storage:** vim.fn.stdpath('data') + JSON files
- **LSP:** Native LSP client (vim.lsp)
- **Notifications:** vim.notify or nvim-notify plugin

#### Sublime Text
- **API:** Sublime Text API
- **Language:** Python
- **Tree View:** Custom view with phantoms or side panel
- **UI:** Input panels, quick panels, HTML sheets
- **Storage:** sublime.Settings
- **LSP:** LSP package
- **Notifications:** Status bar + sublime.message_dialog

---

### 2. Testing Strategy

**Unit Tests:**
- Test API client functions (mock HTTP responses)
- Test state management logic
- Test data transformations
- Test error handling

**Integration Tests:**
- Test API integration against CircleCI staging environment
- Test WebSocket connection and event handling
- Test file system operations (config reading, SSH key access)

**E2E Tests:**
- Automated UI testing (e.g., VSCode extension testing framework)
- Test full user workflows (login → view pipelines → rerun job)

**Test Coverage Target:** 70%+ code coverage

---

### 3. Performance Optimization

**Requirements:**
- **Lazy Loading:** Load pipeline data only when tree node is expanded
- **Pagination:** Use "Load More" instead of loading all data upfront
- **Caching:** Cache API responses for 30-60 seconds
- **Debouncing:** Debounce rapid UI updates (e.g., during WebSocket events)
- **Virtualization:** Use virtual scrolling for large lists (1000+ items)
- **Background Processing:** Run API calls in background threads/workers
- **Startup Time:** Extension should activate in <2 seconds

**Metrics to Monitor:**
- Extension activation time
- API request latency (p50, p95, p99)
- Memory usage
- UI render time

---

### 4. Offline Support

**Requirements:**
- **Graceful Degradation:** Show cached data when offline
- **Error Messages:** Display clear offline indicators
- **Retry Logic:** Automatically retry failed requests when online
- **Sync on Reconnect:** Refresh data when network restored

---

### 5. Accessibility

**Requirements:**
- **Keyboard Navigation:** All UI actions accessible via keyboard
- **Screen Reader Support:** Proper ARIA labels and semantic HTML
- **High Contrast:** Support high contrast themes
- **Focus Indicators:** Visible focus indicators on all interactive elements
- **Text Alternatives:** Alt text for icons and images

---

### 6. Localization (Future)

**Considerations:**
- Externalize all user-facing strings
- Support internationalization (i18n) frameworks
- Start with English (en-US)
- Future: Add support for major languages (es, fr, de, ja, zh)

---

### 7. Versioning & Updates

**Requirements:**
- **Semantic Versioning:** MAJOR.MINOR.PATCH
- **Changelog:** Maintain CHANGELOG.md with all changes
- **Auto-update:** Support editor's auto-update mechanism
- **Migration Scripts:** Run migrations on version updates
- **Breaking Changes:** Clearly document breaking changes

---

### 8. Packaging & Distribution

**VSCode:**
- Package as .vsix file
- Publish to VSCode Marketplace
- Azure DevOps pipeline for CI/CD

**JetBrains:**
- Package as .zip plugin
- Publish to JetBrains Marketplace
- Use Gradle for builds

**Neovim:**
- Distribute via package managers (packer.nvim, lazy.nvim)
- Publish to GitHub

**Sublime:**
- Package as .sublime-package
- Publish to Package Control

---

## Success Metrics

### 1. Adoption Metrics

- **Installs:** Total extension installs
- **Active Users (DAU/MAU):** Daily/monthly active users
- **Retention:** % of users still active after 30/90 days
- **Growth Rate:** Month-over-month install growth

**Targets:**
- Year 1: 10,000 installs (VSCode)
- Year 2: 50,000 installs (VSCode + JetBrains)

---

### 2. Engagement Metrics

- **Commands Executed:** Average commands per user per session
- **Feature Usage:**
  - % users using pipeline monitoring
  - % users using SSH debugging
  - % users using test run feature
  - % users using config validation
- **Session Duration:** Time spent with extension active
- **Notification Interactions:** % of notifications acted upon

**Targets:**
- Average 10+ commands per user per day
- 60%+ users actively monitoring pipelines
- 20%+ users using SSH debugging
- 30%+ users using test run feature

---

### 3. Quality Metrics

- **Crash Rate:** % of sessions with crashes
- **Error Rate:** API errors per 1000 requests
- **Latency:** p95 API response time <2 seconds
- **User Satisfaction:** Average user rating (4.0+/5.0)

**Targets:**
- Crash rate <0.1%
- API error rate <1%
- p95 latency <2s
- User rating >4.0/5.0

---

### 4. Support Metrics

- **Bug Reports:** Number of reported bugs per month
- **Feature Requests:** Number of feature requests
- **Response Time:** Average time to respond to issues (target: <48 hours)
- **Resolution Time:** Average time to fix bugs (target: <2 weeks)

---

## Appendices

### Appendix A: VSCode Extension Reference

**Repository:** `circleci/circleci-vscode-extension`
**Current Version:** 2.11.2
**Architecture:**
- Monorepo with Yarn workspaces
- Packages: vscode-extension, api, telemetry, tools, react-kit, integration-tests
- State Management: Redux Toolkit + RxJS
- LSP: circleci-yaml-language-server (external binary)
- Build: Turbo + Webpack

---

### Appendix B: CircleCI API Documentation

**Official Docs:** https://circleci.com/docs/api/v2/
**API Explorer:** https://circleci.com/docs/api/v2/#section/Authentication
**Rate Limits:** https://circleci.com/docs/api-developers-guide/

---

### Appendix C: Supported VCS Providers

| Provider | VCS Short | Host | Project Slug Format |
|----------|-----------|------|---------------------|
| GitHub | `gh` | github.com | `gh/{org}/{repo}` |
| Bitbucket | `bb` | bitbucket.org | `bb/{org}/{repo}` |
| GitLab | `gl` | gitlab.com | `gl/{org}/{repo}` |
| CircleCI | `circleci` | circleci.com | `circleci/{org}/{project}` |

---

### Appendix D: Extension Feature Matrix

| Feature | Priority | VSCode | JetBrains | Neovim | Sublime |
|---------|----------|--------|-----------|--------|---------|
| Pipeline Monitoring | P0 | ✅ | 🎯 | 🎯 | 🎯 |
| Workflow Actions | P0 | ✅ | 🎯 | 🎯 | 🎯 |
| Job Actions | P0 | ✅ | 🎯 | 🎯 | 🎯 |
| SSH Debugging | P1 | ✅ | 🎯 | 🎯 | 🎯 |
| Config Validation | P0 | ✅ | 🎯 | 🎯 | 🎯 |
| LSP Integration | P0 | ✅ | 🎯 | 🎯 | 🎯 |
| Test Run | P1 | ✅ | 🎯 | 🎯 | 🎯 |
| Real-time Updates | P1 | ✅ | 🎯 | 🎯 | ⚠️ |
| Notifications | P1 | ✅ | 🎯 | 🎯 | 🎯 |
| Test Results | P2 | ✅ | 🎯 | 🎯 | 🎯 |
| Artifacts | P2 | ✅ | 🎯 | 🎯 | 🎯 |
| OAuth Login | P2 | ❌ | 🎯 | ❌ | ❌ |

Legend: ✅ Implemented | 🎯 Planned | ⚠️ Limited Support | ❌ Not Planned

---

### Appendix E: Glossary

- **Pipeline:** A complete run of a CircleCI configuration, triggered by a commit or API call
- **Workflow:** A set of jobs within a pipeline, potentially with dependencies
- **Job:** An individual unit of work (build, test, deploy) within a workflow
- **Project Slug:** Unique identifier for a CircleCI project (format: `vcs/org/repo`)
- **LSP:** Language Server Protocol, a standard for language intelligence features
- **VCS:** Version Control System (GitHub, Bitbucket, GitLab)
- **Orb:** Reusable CircleCI configuration packages
- **Context:** Secure environment variable storage in CircleCI
- **Executor:** Runtime environment for jobs (Docker, Machine, macOS)
- **Parallelism:** Running the same job multiple times with split tests

---

### Appendix F: Related Documentation

- **CircleCI Docs:** https://circleci.com/docs/
- **VSCode Extension Overview:** https://circleci.com/docs/guides/toolkit/vs-code-extension-overview/
- **CircleCI YAML Language Server:** https://github.com/CircleCI-Public/circleci-yaml-language-server
- **CircleCI API v2:** https://circleci.com/docs/api/v2/
- **Language Server Protocol:** https://microsoft.github.io/language-server-protocol/

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-24 | Engineering | Initial PRD based on VSCode extension research |

---

## Approval & Sign-off

_This section to be completed by stakeholders_

- [ ] Engineering Lead
- [ ] Product Manager
- [ ] Design Lead
- [ ] Security Review
- [ ] Legal Review (if applicable)

---

**End of Product Requirements Document**
