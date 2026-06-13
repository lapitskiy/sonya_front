#pragma once

#include <stdbool.h>

typedef enum {
    BUTTON_POLICY_RECORD = 0,
    BUTTON_POLICY_IGNORE_NO_LINK,
} button_policy_decision_t;

button_policy_decision_t button_policy_decide(bool triggered_by_button, bool link_connected);
const char *button_policy_decision_name(button_policy_decision_t decision);
