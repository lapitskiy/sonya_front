# Sonya Watch Firmware v0
# Pipeline: always listen -> wake detected -> record fixed duration -> BLE send to phone

cmake_minimum_required(VERSION 3.16)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)

# Windows quirk: some managed components spawn cmd.exe and call "python" directly.
# Make sure the ESP-IDF venv Scripts dir is visible as "python" for cmd.exe too.
if(WIN32 AND DEFINED ENV{IDF_PYTHON_ENV_PATH})
    set(_idf_py_scripts "$ENV{IDF_PYTHON_ENV_PATH}/Scripts")
    if(EXISTS "${_idf_py_scripts}/python.exe")
        set(ENV{PATH} "${_idf_py_scripts};$ENV{PATH}")
    endif()
endif()

project(sonya_watch)
