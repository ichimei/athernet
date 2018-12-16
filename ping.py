#!/usr/bin/env python3
# Using code from https://gist.github.com/pklaus/856268

import time, socket, struct, select, random

# From /usr/include/linux/icmp.h; your milage may vary.
ICMP_ECHO_REQUEST = 8 # Seems to be the same on Solaris.
PAYLOAD = b'E' * 56

ERROR_DESCR = {
    1: ' - Note that ICMP messages can only be '
       'sent from processes running as root.',
    10013: ' - Note that ICMP messages can only be sent by'
           ' users or processes with administrator rights.'
}

def checksum(source_string):
    # I'm not too confident that this is right but testing seems to
    # suggest that it gives the same answers as in_cksum in ping.c.
    sum = 0
    count_to = (len(source_string) / 2) * 2
    count = 0
    while count < count_to:
        this_val = source_string[count + 1] * 256 + source_string[count]
        sum = sum + this_val
        sum = sum & 0xffffffff # Necessary?
        count = count + 2
    if count_to < len(source_string):
        sum = sum + source_string[len(source_string) - 1]
        sum = sum & 0xffffffff # Necessary?
    sum = (sum >> 16) + (sum & 0xffff)
    sum = sum + (sum >> 16)
    answer = ~sum
    answer = answer & 0xffff
    answer = answer >> 8 | (answer << 8 & 0xff00)
    return answer

def create_packet(packet_id, seq, data):
    """Create a new echo request packet based on the given "id"."""
    # Header is type (8), code (8), checksum (16), id (16), sequence (16)
    header = struct.pack('!bbHHh', ICMP_ECHO_REQUEST, 0, 0, packet_id, seq)
    # Calculate the checksum on the data and the dummy header.
    my_checksum = checksum(header + data)
    # Now that we have the right checksum, we put that in. It's just easier
    # to make up a new header than to stuff it into the dummy.
    header = struct.pack('!bbHHh', ICMP_ECHO_REQUEST, 0, my_checksum, packet_id, seq)
    return header + data

def do_one(dest_addr, packet_id, seq, data, timeout=1):
    """
    Sends one ping to the given "dest_addr" which can be an ip or hostname.
    "timeout" can be any integer or float except negatives and zero.

    Returns either the delay (in seconds) or None on timeout and an invalid
    address, respectively.

    """
    try:
        my_socket = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP)
    except socket.error as e:
        if e.errno in ERROR_DESCR:
            # Operation not permitted
            raise socket.error(''.join((e.args[1], ERROR_DESCR[e.errno])))
        raise # raise the original error
    try:
        socket.gethostbyname(dest_addr)
    except socket.gaierror:
        return
    packet = create_packet(packet_id, seq, data)
    my_socket.sendto(packet, (dest_addr, 0))
    delay = receive_ping(my_socket, packet_id, time.time(), timeout)
    my_socket.close()
    return delay

def receive_ping(my_socket, packet_id, time_sent, timeout):
    # Receive the ping from the socket.
    time_left = timeout
    while True:
        started_select = time.time()
        ready = select.select([my_socket], [], [], time_left)
        how_long_in_select = time.time() - started_select
        if ready[0] == []: # Timeout
            return
        time_received = time.time()
        rec_packet, addr = my_socket.recvfrom(1024)
        icmp_header = rec_packet[20:28]
        data = rec_packet[28:]
        type_, code, chksum, p_id, seq = struct.unpack(
            '!bbHHh', icmp_header)
        repack_header = struct.pack('!bbHHh', type_, code, 0, p_id, seq)
        corr_chksum = checksum(repack_header + data)

        if p_id != packet_id or corr_chksum != chksum:
            time_left -= time_received - time_sent
            if time_left <= 0:
                return
        else:
            return time_received - time_sent

def verbose_ping(dest_addr, timeout=1, count=4):
    """
    Sends one ping to the given "dest_addr" which can be an ip or hostname.

    "timeout" can be any integer or float except negatives and zero.
    "count" specifies how many pings will be sent.

    Displays the result on the screen.

    """
    packet_id = random.randrange(0x10000)
    data = PAYLOAD
    for seq in range(count):
        print('ping {}...'.format(dest_addr))
        delay = do_one(dest_addr, packet_id, seq, data, timeout)
        if delay == None:
            print('failed. (Timeout within {} seconds.)'.format(timeout))
        else:
            delay = round(delay * 1000.0, 4)
            print('get ping in {} milliseconds.'.format(delay))
    print()

if __name__ == '__main__':
    verbose_ping('www.google.com')
