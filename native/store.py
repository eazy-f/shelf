#!/usr/bin/python3

import json
import sys
import os
import struct

class ListCommand:
    def __init__(self):
        pass

    def execute(self, directory):
        files = os.listdir(directory)
        return {'names': files}

def parse_command(command):
    registrations = {
        'list': ListCommand
    }
    command_class = registrations[command['op']]
    return command_class(**command.get('args', {}))

def read_command():
    command = get_message()
    return parse_command(command)

def execute(command, directory):
    message = encode_message(command.execute(directory))
    send_message(message)

def get_message():
    raw_length = sys.stdin.buffer.read(4)
    if not raw_length:
        sys.exit(0)
    message_length = struct.unpack('=I', raw_length)[0]
    message = sys.stdin.buffer.read(message_length)
    return json.loads(message.decode('utf-8'))

# Encode a message for transmission, given its content.
def encode_message(message_content):
    encoded_content = json.dumps(message_content).encode('utf-8')
    encoded_length = struct.pack('=I', len(encoded_content))
    return {'length': encoded_length, 'content': encoded_content}

def send_message(encoded_message):
    sys.stdout.buffer.write(encoded_message['length'])
    sys.stdout.buffer.write(encoded_message['content'])
    sys.stdout.buffer.flush()

def main():
    command = read_command()
    execute(command, '/tmp')

if __name__ == '__main__':
    main()
