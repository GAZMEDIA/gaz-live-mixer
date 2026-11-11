cmake_minimum_required(VERSION 3.16)
project(gaz-live-mixer VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(libobs REQUIRED)
find_package(obs-frontend-api REQUIRED)
find_package(Qt6 REQUIRED COMPONENTS Core Gui Widgets)

add_library(gaz-live-mixer MODULE
  src/gaz-live-mixer.cpp
  third_party/qrcodegen/qrcodegen.cpp)

target_include_directories(gaz-live-mixer PRIVATE third_party)
target_link_libraries(gaz-live-mixer PRIVATE
  OBS::libobs OBS::obs-frontend-api Qt6::Core Qt6::Gui Qt6::Widgets)

if(WIN32)
  target_link_libraries(gaz-live-mixer PRIVATE ws2_32)
  target_compile_definitions(gaz-live-mixer PRIVATE _WIN32_WINNT=0x0A00 NOMINMAX WIN32_LEAN_AND_MEAN)
  target_compile_options(gaz-live-mixer PRIVATE /utf-8 /bigobj)
endif()

set_target_properties(gaz-live-mixer PROPERTIES PREFIX "")

# Web panel files shipped in the plugin's data folder
set(GAZ_DATA ${CMAKE_CURRENT_SOURCE_DIR}/../shared/panel.html ${CMAKE_CURRENT_SOURCE_DIR}/../shared/connect.html)

if(WIN32)
  # Produces the same layout as OBS: <prefix>/obs-plugins/64bit + <prefix>/data/obs-plugins/gaz-live-mixer
  install(TARGETS gaz-live-mixer LIBRARY DESTINATION obs-plugins/64bit RUNTIME DESTINATION obs-plugins/64bit)
  install(FILES ${GAZ_DATA} DESTINATION data/obs-plugins/gaz-live-mixer)
  install(DIRECTORY data/locale DESTINATION data/obs-plugins/gaz-live-mixer)
elseif(APPLE)
  install(TARGETS gaz-live-mixer LIBRARY DESTINATION gaz-live-mixer.plugin/Contents/MacOS)
  install(FILES ${GAZ_DATA} DESTINATION gaz-live-mixer.plugin/Contents/Resources)
  install(DIRECTORY data/locale DESTINATION gaz-live-mixer.plugin/Contents/Resources)
else()
  # Per-user install: ~/.config/obs-studio/plugins/gaz-live-mixer/{bin/64bit,data}
  install(TARGETS gaz-live-mixer LIBRARY DESTINATION gaz-live-mixer/bin/64bit)
  install(FILES ${GAZ_DATA} DESTINATION gaz-live-mixer/data)
  install(DIRECTORY data/locale DESTINATION gaz-live-mixer/data)
endif()
