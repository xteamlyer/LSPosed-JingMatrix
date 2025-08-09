#include "oat.h"

namespace art {

uint32_t OatHeader::GetKeyValueStoreSize() const {
    return *(uint32_t*)((uintptr_t)this + OatHeader::Get_key_value_store_size_Offset());
}

const uint8_t* OatHeader::GetKeyValueStore() const {
    return (const uint8_t*)((uintptr_t)this + OatHeader::Get_key_value_store_Offset());
}

void OatHeader::SetKeyValueStoreSize(uint32_t new_size) {
    *reinterpret_cast<uint32_t*>((uintptr_t)this + Get_key_value_store_size_Offset()) = new_size;
}

}  // namespace art
