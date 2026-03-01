idf_component_register(
    SRCS "sonya_board.c" "pmu_xpowers.cpp"
    INCLUDE_DIRS "include"
    REQUIRES driver esp_driver_i2c XPowersLib
)

