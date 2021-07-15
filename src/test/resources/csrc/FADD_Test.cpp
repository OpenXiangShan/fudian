#include <stdio.h>
#include <stdint.h>
#include <verilated.h>
#include <VFADD.h>
#include "common.h"

int main(int argc, char* argv[]) {
    if(argc != 3){
        printf("usage: %s <rounding-mode> <op>\n", argv[0]);
        return -1;
    }

    int rm = get_str_index(argv[1], rounding_modes, 5);
    if(rm == -1){
        printf("unknown rounding mode: %s\n", argv[1]);
        return -1;
    }

    const char* op_list[] = {"add", "sub"};
    int op = get_str_index(argv[2], op_list, 2);
    if(op == -1){
        printf("unknown op: %s\n", argv[2]);
        return -1;
    }

    VFADD module;

    for(int i = 0; i<10; i++){
        module.reset = 1;
        module.clock = 0;
        module.eval();
        module.clock = 1;
        module.eval();
    }
    module.reset = 0;
    module.clock =0;
    module.eval();
    module.clock = 1;
    module.eval();

    uint64_t a, b, ref_sum, ref_fflags;
    uint64_t dut_sum, dut_fflags;

    uint64_t cnt = 0;
    uint64_t error = 0;

    module.io_rm = rm;
    module.io_do_sub = op;
    while(scanf("%lx %lx %lx %lx", &a, &b, &ref_sum, &ref_fflags) != EOF){
        module.io_a = a;
        module.io_b = b;
        module.clock = 0;
        module.eval();
        module.clock = 1;
        module.eval();
        dut_sum = module.io_result;
        dut_fflags = module.io_fflags;
        if( (dut_sum != ref_sum || dut_fflags != ref_fflags) ){
            printf("[%ld] input: %lx %lx\n", cnt, a, b);
            printf("[%ld] dut_sum: %lx dut_fflags: %lx\n", cnt, dut_sum, dut_fflags);
            printf("[%ld] ref_sum: %lx ref_fflags: %lx\n", cnt, ref_sum, ref_fflags);
            error++;
            return -1;
        }
        cnt++;
    }
    printf("cnt = %ld error=%ld\n", cnt, error);
}
