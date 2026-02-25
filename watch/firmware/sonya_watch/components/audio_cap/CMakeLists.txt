idf_component_register(
    SRCS "audio_cap.c"
    INCLUDE_DIRS "include"
    REQUIRES driver freertos esp_timer
    PRIV_REQUIRES espressif__es7210
)
