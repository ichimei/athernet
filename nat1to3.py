import socket, time, os

UDP_IP = '10.15.28.160'
UDP_PORT = 2333

NOTIFY_FILE = 'udp.notify'
TXT_FILE = 'input.txt.out'

while not os.path.exists(NOTIFY_FILE):
    time.sleep(0.1)

sock = socket.socket(type=socket.SOCK_DGRAM)

with open(TXT_FILE) as txt:
    s = txt.read()

for line in s.splitlines():
    sock.sendto(bytes(line, 'utf-8'), (UDP_IP, UDP_PORT))
