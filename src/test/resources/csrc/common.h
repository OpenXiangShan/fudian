#ifndef __COMMON_H__
#define __COMMON_H__

#include <stdio.h>
#include <stdint.h>
#include <verilated.h>
#include <string.h>

const char* rounding_modes[] = {
    "-rnear_even",
    "-rminMag", // rtz
    "-rmin", // rdown
    "-rmax", // rup
    "-rnear_maxMag", // rmm
};

int get_rounding_mode(char* rm_str) {
    for(int i = 0; i < 5; i++){
        if(strcmp(rm_str, rounding_modes[i]) == 0) return i;
    }
    return -1;
}

int get_str_index(const char* key, const char* str_lst[], int len) {
    for(int i = 0; i < len; i++){
        if(strcmp(key, str_lst[i]) == 0) return i;
    }
    return -1;
}

double sc_time_stamp() { return 0; }

#endif
