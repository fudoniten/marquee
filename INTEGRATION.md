# Service Integration Guide

This document describes the integration of Pseudovision, Tunarr Scheduler, and Tunabrain services into the Marquee UI.

## Overview

Marquee now includes:

1. **Media Tab** - Browse media libraries and items from Pseudovision
2. **Media Detail Page** - View detailed metadata from both Pseudovision and Tunarr Scheduler
3. **Service Integration** - Auto-configured OpenAPI clients using martian-re-frame

## Architecture

### Frontend (ClojureScript)
- Uses `martian-re-frame` to auto-generate API clients from OpenAPI specs
- API calls are dispatched as re-frame events
- State is managed in the re-frame app-db

### Backend (Clojure BFF)
- Proxies all API requests to backend services
- Adds authentication tokens automatically
- Rewrites OpenAPI specs to route through itself
- Keeps credentials server-side for security

## Files Created/Modified

### New Files

#### Pages
- `src/marquee/pages/media.cljs` - Main media browsing page
  - Displays libraries from Pseudovision
  - Shows media items in each library
  - Provides navigation to detail pages

- `src/marquee/pages/media_detail.cljs` - Media detail page
  - Shows metadata from Pseudovision
  - Shows metadata from Tunarr Scheduler
  - Provides back navigation

#### Configuration
- `.envrc.example` - Example environment variables file
- `setup-env.sh` - Quick setup script for environment variables
- `INTEGRATION.md` - This documentation file

### Modified Files

#### Frontend
- `src/marquee/events.cljs` - Added events for:
  - Loading media libraries
  - Loading library items
  - Loading media item details
  - Loading scheduler metadata
  - Navigation to media pages

- `src/marquee/subs.cljs` - Added subscriptions for:
  - Media libraries
  - Library items
  - Current media ID
  - Media item details
  - Scheduler metadata

- `src/marquee/views.cljs` - Updated to:
  - Add media and media-detail pages to routing
  - Support pages that don't show in navigation (media-detail)

#### Documentation
- `README.md` - Updated with:
  - Service integration overview
  - Environment configuration instructions
  - API call examples
  - Architecture documentation

- `.gitignore` - Added `.envrc` and `.env` to prevent committing secrets

## Setup Instructions

### 1. Set Environment Variables

Choose one of the following methods:

#### Option A: Using direnv (recommended)
```bash
cp .envrc.example .envrc
# Edit .envrc and add any required tokens
direnv allow
```

#### Option B: Using the setup script
```bash
source setup-env.sh
```

#### Option C: Manual export
```bash
export PSEUDOVISION_URL=https://pseudovision.kube.sea.fudo.link
export PSEUDOVISION_TOKEN=  # optional

export TUNARR_SCHEDULER_URL=https://tunarr-scheduler.kube.sea.fudo.link
export TUNARR_SCHEDULER_TOKEN=  # optional

export TUNABRAIN_URL=https://tunabrain.kube.sea.fudo.link
export TUNABRAIN_TOKEN=  # optional
```

### 2. Start Development Environment

```bash
# Terminal 1: Start shadow-cljs (frontend)
nix develop
npm install
npx shadow-cljs watch app
# Frontend running at http://localhost:8080

# Terminal 2: Start Tailwind CSS watcher
npx tailwindcss -i src/css/main.css -o public/css/main.css --watch

# Terminal 3: Start BFF server (backend)
source setup-env.sh  # or use direnv
clojure -M:server
# BFF running at http://localhost:3000
```

### 3. Access the Application

Open http://localhost:8080 in your browser and click the "Media" tab.

## API Endpoints Used

### Pseudovision

#### Libraries
- `GET /api/media/libraries` - List all media libraries
- `GET /api/media/libraries/{id}/items` - Get items in a library

#### Media Items
- `GET /api/media/items/{id}` - Get media item details

### Tunarr Scheduler

#### Metadata
- `GET /api/media-item/{media-id}` - Get media item metadata

## Making API Calls

API calls are made using martian-re-frame. The operation IDs are auto-generated from the HTTP method and path.

### Example: Load Media Libraries

```clojure
(rf/dispatch [::martian/request
              :pseudovision                  ;; service ID
              :get-api-media-libraries       ;; operation ID
              {}                             ;; params
              [::on-success]                 ;; success event
              [::on-failure]])               ;; failure event
```

### Operation ID Format

Operation IDs follow the pattern: `{method}-{path-with-dashes}`

Examples:
- `GET /api/media/libraries` → `:get-api-media-libraries`
- `GET /api/media/libraries/{id}/items` → `:get-api-media-libraries-id-items`
- `GET /api/media-item/{media-id}` → `:get-api-media-item-media-id`

### Path Parameters

Path parameters are passed in the params map:

```clojure
(rf/dispatch [::martian/request
              :pseudovision
              :get-api-media-items-id
              {:id 123}                      ;; path param
              [::on-success]
              [::on-failure]])
```

## Future Work: Tunabrain Integration

The architecture is ready for Tunabrain integration. To add it:

1. Identify the relevant Tunabrain endpoints
2. Add events in `events.cljs` for loading Tunabrain data
3. Add subscriptions in `subs.cljs` for accessing Tunabrain data
4. Update the media detail page to display Tunabrain metadata
5. Set the `TUNABRAIN_TOKEN` environment variable if required

## Troubleshooting

### Services not loading
- Check that all environment variables are set: `env | grep -E 'PSEUDOVISION|TUNARR|TUNABRAIN'`
- Verify the BFF server is running: `curl http://localhost:3000/api/pseudovision/openapi.json`
- Check browser console for errors

### API call failures
- Check the browser console for detailed error messages
- Verify the service URLs are accessible: `curl https://pseudovision.kube.sea.fudo.link/health`
- Check BFF server logs for proxy errors

### Build errors
- Make sure you've run `npm install` in the nix develop shell
- Check that all ClojureScript files have valid syntax
- Try clearing shadow-cljs cache: `rm -rf .shadow-cljs`

## Testing the Integration

### Manual Testing Steps

1. **Start all services** (see Setup Instructions above)
2. **Navigate to Media tab** - Should see "Loading libraries..."
3. **Libraries should load** - Should see library names and their items
4. **Click "View Details"** on any media item - Should navigate to detail page
5. **Detail page should show**:
   - Pseudovision data (media item info)
   - Tunarr Scheduler metadata (if available)
   - Back button to return to media list

### Expected Behavior

- Loading states show while fetching data
- Empty states show when no data is available
- Error messages are logged to console (check browser DevTools)
- Navigation works smoothly between pages

## Code Structure

### Event Flow

```
User clicks Media tab
  → [::events/navigate :media]
  → [::events/load-media-libraries]
  → [::martian/request :pseudovision :get-api-media-libraries ...]
  → [::events/load-media-libraries-success]
  → For each library: [::events/load-library-items]
  → [::martian/request :pseudovision :get-api-media-libraries-id-items ...]
  → [::events/load-library-items-success]
  → UI updates with @(rf/subscribe [::subs/library-items])
```

### Data Flow

```
app-db
  :media-libraries [...]           ;; List of libraries
  :library-items {lib-id [...]}    ;; Items by library ID
  :media-items {item-id {...}}     ;; Item details by ID
  :scheduler-metadata {id {...}}   ;; Scheduler data by ID
  :current-media-id 123            ;; Currently viewed item
```

## Security Notes

- **Never commit `.envrc` or `.env` files** - They contain secrets
- **Tokens are stored server-side** - Frontend never sees them
- **BFF adds tokens** - All proxied requests get the auth header
- **HTTPS required** - Service URLs should use https://

## Resources

- [martian-re-frame documentation](https://github.com/oliyh/martian)
- [re-frame documentation](https://day8.github.io/re-frame/)
- [OpenAPI specification](https://swagger.io/specification/)
