# Explicitly set libgl4es.so to SONAME libGL.so, the JVM will
# bind by SONAME and prevent collisions that way.

cmake_language(DEFER DIRECTORY "${CMAKE_SOURCE_DIR}" CALL
    set_target_properties GL PROPERTIES
        NO_SONAME TRUE
        LINK_FLAGS "-Wl,-soname,libGL.so"
)
