typedef struct Vec3 { float x; float y; float z; } Vec3;

typedef struct Pose {
    Vec3  position;   /* +0x00 */
    float q[4];       /* +0x0c */
} Pose;               /* sizeof 28 */

typedef struct Node {
    void*         next;  /* +0x00 */
    Pose          pose;  /* +0x08 */
    unsigned int  flags; /* +0x24 */
    unsigned char kind;  /* +0x28 */
    char          _pad[7];
} Node;                  /* sizeof 48 */
