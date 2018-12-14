import socket, time, os

UDP_IP = '10.20.193.12'
UDP_PORT = 2333
END_MSG = b'\x12\x34\x56'

TXT_FILE = 'input.txt'

sock = socket.socket(type=socket.SOCK_DGRAM)

with open(TXT_FILE) as txt:
    s = txt.read()

for line in s.splitlines():
    sock.sendto(bytes(line, 'utf-8'), (UDP_IP, UDP_PORT))

# end message
sock.sendto(END_MSG, (UDP_IP, UDP_PORT))
