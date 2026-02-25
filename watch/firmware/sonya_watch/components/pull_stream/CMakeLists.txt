idf_component_register(
    SRCS "pull_stream.c"
    INCLUDE_DIRS "include"
    REQUIRES freertos sonya_ble protocol rec_store esp_common
)
