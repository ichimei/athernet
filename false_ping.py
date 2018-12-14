import time, os, shutil

FILE_REQ = 'icmp_req.bin'
FILE_REP = 'icmp_rep.bin'
FILE_REQ_NOTIFY = 'icmp_req.bin.notify'
FILE_REP_NOTIFY = 'icmp_rep.bin.notify'

while True:
    while not os.path.exists(FILE_REQ_NOTIFY):
        time.sleep(0.1)

    os.remove(FILE_REQ_NOTIFY)
    print("Packet")
    shutil.copyfile(FILE_REQ, FILE_REP)
    open(FILE_REP_NOTIFY, 'w').close()
