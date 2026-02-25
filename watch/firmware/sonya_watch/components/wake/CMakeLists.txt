idf_component_register(
    SRCS "wake.c"
    INCLUDE_DIRS "include"
    REQUIRES driver freertos audio_cap espressif__esp-sr
)
