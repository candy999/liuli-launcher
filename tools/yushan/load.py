import sys, gzip, numpy as np
def load(path):
    d=np.frombuffer(gzip.open(path).read(),dtype='>i2').reshape(3601,3601).astype(np.float32)
    d[d<-1000]=0
    return d
def rc(lat,lon): return (24-lat)*3600,(lon-120)*3600
def peak(d,lat,lon,rad_s=40):
    r,c=rc(lat,lon); r=int(r); c=int(c)
    w=d[r-rad_s:r+rad_s,c-rad_s:c+rad_s]; i=np.unravel_index(w.argmax(),w.shape)
    rr=r-rad_s+i[0]; cc=c-rad_s+i[1]
    return 24-rr/3600,120+cc/3600,w.max()
if __name__=='__main__':
    d=load(sys.argv[1])
    for n,la,lo in [('祝山',23.513,120.811),('玉山主峰',23.470,120.957),('北峰',23.4875,120.9597),('東峰',23.470,120.962),('西峰',23.4706,120.937),('南峰',23.439,120.958),('前峰',23.480,120.92)]:
        print(n,peak(d,la,lo))
