import socket

UDP_IP = '10.20.192.173'
UDP_PORT = 5005
END_MSG = b'\x12\x34\x56'

DATA_FILE = './data.bin'
FRAME_BYTES = 50

NOTIFY_FILE = './tmp/natr.notify'

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((UDP_IP, UDP_PORT))

with open(DATA_FILE, 'wb') as data_file:
    while True:
        data, addr = sock.recvfrom(1024)
        if data == END_MSG:
            print('End!')
            break
        data_file.write(socket.inet_aton(addr[0]))
        data_file.write(addr[1].to_bytes(2, 'little'))
        data_file.write(data)
        data_file.write(bytes(FRAME_BYTES - 6 - len(data)))
        print('recv from {}: {}'.format(addr, data))

open(NOTIFY_FILE, 'w').close()
