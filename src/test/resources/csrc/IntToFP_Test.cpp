#include <VIntToFP.h>
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

    const char* op_list[] = {"ui32_to_f", "i32_to_f", "ui64_to_f", "i64_to_f"};
    int op = get_str_index(argv[2], op_list, 4);
    if(op == -1){
        printf("unknown op: %s\n", argv[2]);
        return -1;
    }

    VIntToFP module;

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

    uint64_t a, ref_result, ref_fflags;
    uint64_t dut_result, dut_fflags;

    uint64_t cnt = 0;
    uint64_t error = 0;

    module.io_rm = rm;
    module.io_sign = op & 1;
    module.io_long = (op & 2) >> 1;
    while(scanf("%lx %lx %lx", &a, &ref_result, &ref_fflags) != EOF){
        module.io_int = a;
        module.clock = 0;
        module.eval();
        module.clock = 1;
        module.eval();
        dut_result = module.io_result;
        dut_fflags = module.io_fflags;
        if( (dut_result != ref_result || dut_fflags != ref_fflags) ){
            printf("[%ld] input: %lx\n", cnt, a);
            printf("[%ld] dut_result: %lx dut_fflags: %lx\n", cnt, dut_result, dut_fflags);
            printf("[%ld] ref_result: %lx ref_fflags: %lx\n", cnt, ref_result, ref_fflags);
            error++;
            return -1;
        }
        cnt++;
    }
    printf("cnt = %ld error=%ld\n", cnt, error);
}
