import socket

def listen():
    s = socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    s.bind(('0.0.0.0', 2333))
    while 1:
        print('eee..')
        data, addr = s.recvfrom(1024)
        print("Packet from %r: %r" % (addr,data))

listen()
