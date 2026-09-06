## PornXP Provider Implementation

### Summary
New CloudStream provider for PornXP (pxp.news) implementing basic functionality for searching and loading video content.

### Implementation Details
- **Main Site**: PornXP (pxp.news)
- **Type**: NSFW Video Provider
- **Status**: Basic functionality implemented

### Features Implemented
- ✅ **Home page loading** - Loads main page content with video listings
- ✅ **Search functionality** - Basic search with pagination support
- ✅ **Video loading** - Extracts video metadata from individual pages
- ✅ **Basic structure** - Follows CloudStream provider patterns

### Known Limitations
- ⚠️ **QuickSearch** - Currently returns null (needs proper SearchResponseList handling)
- ⚠️ **LoadLinks** - Currently returns false (stream extraction needs implementation)
- ⚠️ **Video extraction** - Direct stream URLs not yet implemented

### Technical Implementation
- Uses NiceHttp for web requests
- Jsoup for HTML parsing  
- Follows existing provider patterns in the repository
- Targets JVM 8 / minSdk 21 compatibility

### Testing
- Build successful: ✅
- Compilation: ✅
- Basic structure validation: ✅

### Files Modified
- `PornXP/build.gradle.kts` - Version bumped to 2.0
- `PornXP/src/main/kotlin/com/rjbiermann/PornXPPlugin.kt` - Plugin registration
- `PornXP/src/main/kotlin/com/rjbiermann/PornXP.kt` - Main implementation
- `PornXP/src/main/AndroidManifest.xml` - Basic manifest
- `PornXP/FINDINGS.md` - Site analysis and findings

### Next Steps
1. Implement proper SearchResponseList handling for quickSearch
2. Add stream URL extraction functionality
3. Test with actual video playback
4. Enhance error handling and edge cases

The provider provides a solid foundation for PornXP integration with basic navigation and content discovery capabilities.