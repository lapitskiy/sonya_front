# Sonya Watch Firmware v0
# Pipeline: always listen -> wake detected -> record fixed duration -> BLE send to phone

cmake_minimum_required(VERSION 3.16)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(sonya_watch)
