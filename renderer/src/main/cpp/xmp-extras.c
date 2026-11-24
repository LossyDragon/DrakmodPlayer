// Built as part of xmp_static so it sees libxmp's internals with libxmp's own build flags.
#include "libxmp/src/common.h"

// C5 replay rate of a sample: what the mixer uses, not in the public API yet.
double xmp_extra_c5spd(xmp_context opaque, int smp) {
  struct context_data* ctx = (struct context_data*)opaque;
  struct module_data* m = &ctx->m;
  if (smp < 0 || smp >= m->mod.smp || m->xtra == NULL) return m->c4rate;
  return m->xtra[smp].c5spd;
}
