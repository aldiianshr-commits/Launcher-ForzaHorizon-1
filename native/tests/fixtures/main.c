// Test fixture: stands in for libmain.so.
int fhtest_dep_value(void);
int SDL_main(int argc, char** argv) { (void)argc; (void)argv; return fhtest_dep_value(); }
