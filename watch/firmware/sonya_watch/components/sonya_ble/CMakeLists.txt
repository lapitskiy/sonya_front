idf_component_register(
    SRCS "sonya_ble.c"
    INCLUDE_DIRS "include"
    REQUIRES bt nvs_flash protocol
)
