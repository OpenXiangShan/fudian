#include <VFDIV.h>
#include "common.h"
#include "verilated_vcd_c.h"

int main(int argc, char* argv[]) {
    if(argc != 3){
        printf("usage: %s <rounding-mode>\n", argv[0]);
        return -1;
    }

    int rm = get_str_index(argv[1], rounding_modes, 5);
    if(rm == -1){
        printf("unknown rounding mode: %s\n", argv[1]);
        return -1;
    }

    const char* op_list[] = {"div", "sqrt"};
    int op = get_str_index(argv[2], op_list, 4);
    if(op == -1){
        printf("unknown op: %s\n", argv[2]);
        return -1;
    }
    const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

    VFDIV module;
    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    module.trace(tfp, 99);  // Trace 99 levels of hierarchy
    tfp->open("/bigdata/lqr/simx.vcd");

    for(int i = 0; i<10; i++){
        module.reset = 1;
        module.clock = 0;
        module.eval();
        module.clock = 1;
        module.eval();
    }
    module.reset = 0;
    module.clock = 0;
    contextp->timeInc(1);
    module.eval();
    tfp->dump(contextp->time());

    module.clock = 1;
    contextp->timeInc(1);
    module.eval();
    tfp->dump(contextp->time());

    uint64_t a, b, ref_result, ref_fflags;
    uint64_t dut_result, dut_fflags;

    uint64_t cnt = 0;
    uint64_t error = 0;

    while(scanf("%lx %lx %lx %lx", &a, &b, &ref_result, &ref_fflags) != EOF){
        module.clock = 0;
                module.io_a = a;
                module.io_b = b;
                module.io_specialIO_isSqrt = op;
                module.io_rm = rm;
                module.io_specialIO_in_valid = 1;
                module.io_specialIO_out_ready = 1;
        contextp->timeInc(1);
        module.eval();
        tfp->dump(contextp->time());

        module.clock = 1;
        contextp->timeInc(1);
        module.eval();
        tfp->dump(contextp->time());
        while(!module.io_specialIO_out_valid){
            module.clock = 0;
            contextp->timeInc(1);
            module.eval();
            tfp->dump(contextp->time());
            module.clock = 1;
            contextp->timeInc(1);
            module.eval();
            tfp->dump(contextp->time());
        }
        dut_result = module.io_result;
        dut_fflags = module.io_fflags;
        if( (dut_result != ref_result || dut_fflags != ref_fflags) ){
            printf("[%ld] input: %lx %lx\n", cnt, a, b);
            printf("[%ld] dut_sum: %lx dut_fflags: %lx\n", cnt, dut_result, dut_fflags);
            printf("[%ld] ref_sum: %lx ref_fflags: %lx\n", cnt, ref_result, ref_fflags);
            error++;
            tfp->close();
            return -1;
        }
        cnt++;
    }
    printf("cnt = %ld error=%ld\n", cnt, error);
}
